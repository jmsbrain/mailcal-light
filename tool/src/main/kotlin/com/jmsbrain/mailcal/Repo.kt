package com.jmsbrain.mailcal

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Account(val server: String, val user: String, val home: String)

data class CalState(val info: CalInfo, val hidden: Boolean)

/** An occurrence and where it lives. */
data class Item(val cal: CalInfo, val href: String, val occ: Occurrence)

data class UiState(
    val account: Account? = null,
    val calendars: List<CalState> = emptyList(),
    val defaultCal: String? = null,
    val items: List<Item> = emptyList(),
    val syncing: Boolean = false,
    val error: String? = null,
    val updatedAt: Long? = null,
)

/**
 * The account, the calendar list and an offline copy of each calendar.
 * The server is the only real copy: edits go straight to it, then the copy is refreshed.
 */
object Repo {
    const val DEFAULT_SERVER = "https://dav.mailbox.org/"

    /** Agenda range around today. */
    const val PAST_DAYS = 30L
    const val FUTURE_DAYS = 365L

    private lateinit var prefs: Prefs
    private lateinit var cacheDir: File
    private val lock = Mutex()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var dav: CalDav? = null

    /** Calendar URL to its downloaded files. */
    private val resources = mutableMapOf<String, List<Resource>>()
    private val parsed = mutableMapOf<String, List<IcsEvent>>()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val zone: ZoneId get() = ZoneId.systemDefault()

    @Synchronized
    fun init(context: SealedLightContext) {
        if (::prefs.isInitialized) return
        prefs = Prefs(File(context.filesDir, "mailcal.json"))
        cacheDir = File(context.filesDir, "calendars").apply { mkdirs() }
        val account = loadAccount()
        val cals = loadCalendars()
        if (account != null) {
            dav = password()?.let { CalDav(account.user, it) }
            cals.forEach { c -> resources[c.info.url] = readCache(c.info.url).second }
        }
        _state.value = UiState(
            account = account,
            calendars = cals,
            defaultCal = prefs.getString("default", null),
            updatedAt = prefs.getLong("updated", 0).takeIf { it > 0 },
        )
        rebuild()
    }

    /** Keeps the background refresh scheduled; called when the app opens. */
    fun scheduleSync(ctx: SealedLightContext) {
        if (_state.value.account != null) SyncWorker.start(ctx)
    }

    // ---- account ----

    suspend fun signIn(ctx: SealedLightContext, server: String, user: String, password: String) = withContext(Dispatchers.IO) {
        val client = CalDav(user.trim(), password)
        val home = client.findHome(server.ifBlank { DEFAULT_SERVER })
        val cals = client.calendars(home)
        if (cals.isEmpty()) throw DavError("Signed in, but this account has no calendars.")
        lock.withLock {
            dav = client
            savePassword(password)
            prefs.put(
                "server" to server.ifBlank { DEFAULT_SERVER },
                "user" to user.trim(),
                "home" to home,
            )
            val states = cals.map { CalState(it.copy(ctag = null), hidden = false) }
            saveCalendars(states)
            _state.update { UiState(account = loadAccount(), calendars = states, defaultCal = cals.firstOrNull { !it.readOnly }?.url) }
            prefs.put("default" to _state.value.defaultCal)
        }
        // Outlives the sign-in screen.
        appScope.launch { sync() }
        SyncWorker.start(ctx)
    }

    fun signOut(ctx: SealedLightContext) {
        SyncWorker.stop(ctx)
        prefs.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
        resources.clear()
        parsed.clear()
        dav = null
        _state.value = UiState()
        rebuild()
    }

    private fun loadAccount(): Account? {
        val user = prefs.getString("user", null) ?: return null
        val home = prefs.getString("home", null) ?: return null
        return Account(prefs.getString("server", DEFAULT_SERVER)!!, user, home)
    }

    // ---- sync ----

    /** Downloads every feed again, even if the screen changes. */
    fun refreshFeeds() {
        appScope.launch { Feeds.refreshAll(force = true) }
    }

    fun refreshFeed(id: String) {
        appScope.launch { Feeds.refresh(id) }
    }

    /** Feeds older than an hour, from the app or the background job. */
    fun refreshFeedsIfStale() {
        appScope.launch { Feeds.refreshAll() }
    }

    fun source() = if (prefs.getString("source", null) == Source.Feeds.name) Source.Feeds else Source.Mailbox
    fun setSource(s: Source) = prefs.put("source" to s.name)

    /** Refreshes calendars that changed, even if the screen changes. */
    fun refreshChanged() {
        if (!_state.value.syncing) appScope.launch { sync() }
    }

    /** Full refresh that keeps going if the screen changes. */
    fun refresh() {
        if (!_state.value.syncing) appScope.launch { sync(force = true) }
    }

    /** Refreshes the calendar list and any calendar that changed (all of them if [force]). */
    suspend fun sync(force: Boolean = false) {
        val account = _state.value.account ?: return
        val client = dav ?: run {
            _state.update { it.copy(error = "Sign in again to refresh.") }
            return
        }
        _state.update { it.copy(syncing = true, error = null) }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val fresh = client.calendars(account.home)
                val old = _state.value.calendars.associateBy { it.info.url }
                val states = fresh.map { CalState(it, old[it.url]?.hidden ?: false) }
                for (cal in fresh) {
                    val (cachedTag, cached) = readCache(cal.url)
                    if (!force && cal.ctag != null && cal.ctag == cachedTag && cached.isNotEmpty()) continue
                    fetch(client, cal)
                }
                // Forget calendars that are gone.
                val keep = fresh.map { it.url }.toSet()
                lock.withLock {
                    resources.keys.retainAll(keep)
                    parsed.keys.retainAll { k -> keep.any { k.startsWith(it) } }
                }
                cacheDir.listFiles()?.filter { f -> keep.none { cacheName(it) == f.name } }?.forEach { it.delete() }
                saveCalendars(states)
                states
            }
        }
        result.onSuccess { states ->
            val now = System.currentTimeMillis()
            prefs.put("updated" to now)
            _state.update { s ->
                val def = s.defaultCal?.takeIf { d -> states.any { it.info.url == d && !it.info.readOnly } }
                    ?: states.firstOrNull { !it.info.readOnly }?.info?.url
                s.copy(calendars = states, defaultCal = def, syncing = false, updatedAt = now)
            }
        }.onFailure { e ->
            _state.update { it.copy(syncing = false, error = e.message ?: "Couldn't refresh.") }
        }
        rebuild()
    }

    private suspend fun fetch(client: CalDav, cal: CalInfo) {
        val today = LocalDate.now(zone).atStartOfDay(zone)
        // Wider than the agenda so repeats that started earlier come along.
        val list = client.events(cal.url, today.minusDays(PAST_DAYS + 30), today.plusDays(FUTURE_DAYS + 30))
        lock.withLock {
            resources[cal.url] = list
            parsed.keys.removeAll { it.startsWith(cal.url) }
        }
        writeCache(cal.url, cal.ctag, list)
    }

    private suspend fun refetch(cal: CalInfo) {
        val client = dav ?: return
        withContext(Dispatchers.IO) {
            val fresh = runCatching { client.calendars(_state.value.account!!.home).firstOrNull { it.url == cal.url } }.getOrNull()
            fetch(client, fresh ?: cal.copy(ctag = null))
        }
        _state.update { it.copy(error = null) }
        rebuild()
    }

    /** Recomputes the agenda from the offline copy. */
    fun rebuild() {
        val s = _state.value
        val today = LocalDate.now(zone).atStartOfDay(zone)
        val from = today.minusDays(PAST_DAYS)
        val to = today.plusDays(FUTURE_DAYS)
        val items = mutableListOf<Item>()
        for (c in s.calendars) {
            if (c.hidden) continue
            for (r in resources[c.info.url].orEmpty()) {
                val events = parsed.getOrPut(r.href) { runCatching { Ics.parse(r.ics) }.getOrDefault(emptyList()) }
                Ics.expand(events, from, to, zone).forEach { items += Item(c.info, r.href, it) }
            }
        }
        items.sortWith(compareBy({ it.occ.start.toLocalDate() }, { !it.occ.allDay }, { it.occ.start }, { it.occ.title }))
        _state.update { it.copy(items = items) }
    }

    // ---- editing ----

    fun resource(item: Item): Resource? = resources[item.cal.url]?.firstOrNull { it.href == item.href }

    suspend fun create(calUrl: String, d: Draft) {
        val cal = _state.value.calendars.first { it.info.url == calUrl }.info
        val client = dav ?: throw DavError("Sign in again to make changes.")
        withContext(Dispatchers.IO) {
            val (text, uid) = VCal.create(d, zone)
            val base = if (cal.url.endsWith("/")) cal.url else cal.url + "/"
            client.put(base + uid + ".ics", text, null)
        }
        refetch(cal)
    }

    suspend fun saveAll(item: Item, d: Draft) = write(item) { VCal.updateAll(it, item.occ, d, zone) }
    suspend fun saveOne(item: Item, d: Draft) = write(item) { VCal.updateOne(it, item.occ, d, zone) }
    suspend fun deleteOne(item: Item) = write(item) { VCal.deleteOne(it, item.occ, zone) }

    suspend fun deleteAll(item: Item) {
        val client = dav ?: throw DavError("Sign in again to make changes.")
        val r = resource(item) ?: throw DavError("This event is no longer here.")
        try {
            withContext(Dispatchers.IO) { client.delete(r.href, r.etag) }
        } catch (e: DavError) {
            if (e.status == 412 || e.status == 404) refetch(item.cal)
            if (e.status != 404) throw e
        }
        refetch(item.cal)
    }

    private suspend fun write(item: Item, change: (String) -> String) {
        val client = dav ?: throw DavError("Sign in again to make changes.")
        val r = resource(item) ?: throw DavError("This event is no longer here.")
        try {
            withContext(Dispatchers.IO) { client.put(r.href, change(r.ics), r.etag) }
        } catch (e: DavError) {
            if (e.status == 412) refetch(item.cal)
            throw e
        }
        refetch(item.cal)
    }

    fun setHidden(url: String, hidden: Boolean) {
        _state.update { s -> s.copy(calendars = s.calendars.map { if (it.info.url == url) it.copy(hidden = hidden) else it }) }
        saveCalendars(_state.value.calendars)
        rebuild()
    }

    fun setDefault(url: String) {
        prefs.put("default" to url)
        _state.update { it.copy(defaultCal = url) }
    }

    // ---- files ----

    private fun saveCalendars(list: List<CalState>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().put("url", c.info.url).put("name", c.info.name).put("readOnly", c.info.readOnly).put("hidden", c.hidden))
        }
        prefs.put("calendars" to arr.toString())
    }

    private fun loadCalendars(): List<CalState> {
        val arr = runCatching { JSONArray(prefs.getString("calendars", "[]")) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CalState(CalInfo(o.getString("url"), o.optString("name"), null, o.optBoolean("readOnly")), o.optBoolean("hidden"))
        }
    }

    private fun cacheName(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".json"

    private fun readCache(url: String): Pair<String?, List<Resource>> {
        val f = File(cacheDir, cacheName(url))
        if (!f.isFile) return null to emptyList()
        return runCatching {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("items")
            o.optString("ctag").ifBlank { null } to (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                Resource(r.getString("href"), r.optString("etag").ifBlank { null }, r.getString("ics"))
            }
        }.getOrDefault(null to emptyList())
    }

    private fun writeCache(url: String, ctag: String?, list: List<Resource>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("href", it.href).put("etag", it.etag ?: "").put("ics", it.ics)) }
        val f = File(cacheDir, cacheName(url))
        val tmp = File(cacheDir, f.name + ".tmp")
        tmp.writeText(JSONObject().put("ctag", ctag ?: "").put("items", arr).toString())
        tmp.renameTo(f)
    }

    // ---- password, encrypted with a key kept in the Android Keystore ----

    private const val KEY_ALIAS = "mailcal-password"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun savePassword(password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(password.toByteArray())
        prefs.put(
            "pw" to Base64.encodeToString(ct, Base64.NO_WRAP),
            "pwIv" to Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }

    private fun password(): String? = runCatching {
        val ct = Base64.decode(prefs.getString("pw", null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(prefs.getString("pwIv", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(ct))
    }.getOrNull()
}

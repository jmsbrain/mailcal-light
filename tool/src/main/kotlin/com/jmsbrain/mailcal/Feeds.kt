package com.jmsbrain.mailcal

import com.thelightphone.sdk.SealedLightContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** A subscribed .ics feed (read-only), from SubcalScriber. */
data class Feed(
    val id: String,
    val name: String,
    val url: String,
    val hidden: Boolean = false,
    val updatedAt: Long? = null,
    val error: String? = null,
)

data class FeedsState(
    val feeds: List<Feed> = emptyList(),
    val items: List<Item> = emptyList(),
    val refreshing: Boolean = false,
)

/** Subscribed .ics feeds and their downloaded files. Only the feed addresses are ever fetched. */
object Feeds {
    /** Feeds older than this are refreshed when the app opens or in the background. */
    const val STALE_MS = 60 * 60_000L

    private lateinit var dir: File
    private val lock = Mutex()
    private val parsed = mutableMapOf<String, List<IcsEvent>>()
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private val _state = MutableStateFlow(FeedsState())
    val state: StateFlow<FeedsState> = _state

    private val listFile get() = File(dir, "feeds.json")
    private fun icsFile(id: String) = File(dir, "feeds/$id.ics")

    @Synchronized
    fun init(context: SealedLightContext) {
        if (::dir.isInitialized) return
        dir = context.filesDir
        _state.value = FeedsState(feeds = load())
        rebuild()
    }

    fun feed(id: String) = _state.value.feeds.firstOrNull { it.id == id }

    // ---- list ----

    private fun load(): List<Feed> = runCatching {
        val arr = JSONArray(listFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Feed(
                id = o.getString("id"),
                name = o.optString("name"),
                url = o.getString("url"),
                hidden = o.optBoolean("hidden"),
                updatedAt = o.optLong("updatedAt").takeIf { it > 0 },
                error = o.optString("error").ifBlank { null },
            )
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<Feed>) {
        _state.update { it.copy(feeds = list) }
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(
                JSONObject().put("id", f.id).put("name", f.name).put("url", f.url).put("hidden", f.hidden)
                    .put("updatedAt", f.updatedAt ?: 0).put("error", f.error ?: ""),
            )
        }
        val tmp = File(dir, "feeds.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(listFile)
    }

    suspend fun save(feed: Feed) = lock.withLock {
        val old = feed(feed.id)
        write(_state.value.feeds.map { if (it.id == feed.id) feed else it }.let { if (old == null) it + feed else it })
        if (old != null && old.url != feed.url) {
            parsed.remove(feed.id)
            withContext(Dispatchers.IO) { icsFile(feed.id).delete() }
        }
        rebuild()
    }

    suspend fun delete(id: String) = lock.withLock {
        parsed.remove(id)
        withContext(Dispatchers.IO) { icsFile(id).delete() }
        write(_state.value.feeds.filterNot { it.id == id })
        rebuild()
    }

    /**
     * Adds every new calendar link in [text] as a feed, named after the calendar
     * (or the text before the link, or its web address). Returns how many were added.
     */
    suspend fun addLinks(text: String): Int {
        var added = 0
        for (link in findLinks(text)) {
            if (_state.value.feeds.any { sameFeed(it.url, link.url) }) continue
            val host = hostOf(httpUrl(link.url))
            val feed = Feed(id = UUID.randomUUID().toString(), name = link.name ?: host, url = link.url)
            save(feed)
            refresh(feed.id)
            if (link.name == null) nameAfterCalendar(feed.id)
            added++
        }
        return added
    }

    private suspend fun nameAfterCalendar(id: String) {
        val name = withContext(Dispatchers.IO) { icsFile(id).takeIf { it.isFile }?.readText()?.let(Ics::calendarName) } ?: return
        lock.withLock {
            val current = feed(id) ?: return@withLock
            write(_state.value.feeds.map { if (it.id == id) current.copy(name = name) else it })
        }
        rebuild()
    }

    // ---- downloading ----

    /** Refreshes feeds older than [STALE_MS] (all of them if [force]). */
    suspend fun refreshAll(force: Boolean = false) {
        if (_state.value.refreshing) return
        val now = System.currentTimeMillis()
        val due = _state.value.feeds.filter { force || (it.updatedAt ?: 0) < now - STALE_MS }
        if (due.isEmpty()) return
        _state.update { it.copy(refreshing = true) }
        try {
            due.forEach { refresh(it.id) }
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    private class FeedError(message: String) : Exception(message)
    private class Download(val url: String, val status: Int, val body: String)

    private fun get(url: String): Download {
        val host = hostOf(url)
        try {
            client.newCall(Request.Builder().url(url).header("Accept", "text/calendar, */*").build()).execute().use { r ->
                return Download(r.request.url.toString(), r.code, r.body.string())
            }
        } catch (e: IOException) {
            throw FeedError(
                when (e) {
                    is javax.net.ssl.SSLException -> "The phone doesn't trust the security certificate of $host."
                    is java.net.UnknownHostException -> "Couldn't reach $host. Check the address and that the phone is online."
                    is java.net.SocketTimeoutException -> "$host took too long to answer."
                    else -> "Couldn't connect to $host."
                },
            )
        } catch (e: IllegalArgumentException) {
            throw FeedError("That isn't a web address.")
        }
    }

    /** Follows short links and turns Google share links into the calendar's .ics address. */
    private fun fetchCalendar(link: String): Pair<String, String> {
        var d = get(googleIcsUrl(httpUrl(link)) ?: httpUrl(link))
        if (!d.body.contains("BEGIN:VCALENDAR")) {
            googleIcsUrl(d.url)?.let { ics ->
                d = get(ics)
                if (d.status == 404 || d.status == 403) throw FeedError(GOOGLE_PRIVATE)
            }
        }
        if (d.status !in 200..299) {
            throw FeedError(
                if (hostOf(d.url).endsWith("google.com") && (d.status == 404 || d.status == 403)) GOOGLE_PRIVATE
                else "${hostOf(d.url)} said ${d.status}. Check the address."
            )
        }
        if (!d.body.contains("BEGIN:VCALENDAR")) {
            throw FeedError("That link opens a web page, not a calendar feed. Look for an address ending in .ics or starting with webcal://.")
        }
        return d.url to d.body
    }

    /** Downloads one feed. Keeps the old copy if anything goes wrong. */
    suspend fun refresh(id: String) {
        val feed = feed(id) ?: return
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val (finalUrl, body) = fetchCalendar(feed.url)
                icsFile(id).parentFile?.mkdirs()
                icsFile(id).writeText(body)
                lock.withLock { parsed[id] = Ics.parse(body) }
                finalUrl
            }
        }
        lock.withLock {
            val current = feed(id) ?: return@withLock
            val updated = result.fold(
                // Keep the real address so short links aren't needed again.
                onSuccess = { current.copy(url = if (resolvedIsBetter(current.url, it)) it else current.url, updatedAt = System.currentTimeMillis(), error = null) },
                onFailure = { current.copy(error = (it as? FeedError)?.message ?: "Couldn't download this feed.") },
            )
            write(_state.value.feeds.map { if (it.id == id) updated else it })
        }
        rebuild()
    }

    // Google share links and short links that land on a calendar file; not other redirects, which may be temporary.
    private fun resolvedIsBetter(original: String, resolved: String): Boolean {
        val o = httpUrl(original)
        if (o == resolved) return false
        if (googleIcsUrl(o) != null) return true
        return hostOf(o) != hostOf(resolved) && (".ics" in resolved || "/ical/" in resolved)
    }

    // ---- agenda ----

    /** Recomputes the feed agenda (same range as the mailbox one). */
    fun rebuild() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone).atStartOfDay(zone)
        val from = today.minusDays(Repo.PAST_DAYS)
        val to = today.plusDays(Repo.FUTURE_DAYS)
        val items = mutableListOf<Item>()
        for (f in _state.value.feeds) {
            if (f.hidden) continue
            val events = parsed.getOrPut(f.id) {
                icsFile(f.id).takeIf { it.isFile }?.let { runCatching { Ics.parse(it.readText()) }.getOrNull() }.orEmpty()
            }
            val cal = CalInfo(FEED_PREFIX + f.id, f.name, null, readOnly = true)
            Ics.expand(events, from, to, zone).forEach { items += Item(cal, FEED_PREFIX + f.id, it) }
        }
        items.sortWith(compareBy({ it.occ.start.withZoneSameInstant(zone).toLocalDate() }, { !it.occ.allDay }, { it.occ.start }, { it.occ.title }))
        _state.update { it.copy(items = items) }
    }

    const val FEED_PREFIX = "feed:"

    // ---- links ----

    /** webcal:// links are just https, and plain http is tried as https (Android blocks http). */
    fun httpUrl(url: String): String {
        val u = url.trim()
        return when {
            u.startsWith("webcals://", true) -> "https://" + u.substring(10)
            u.startsWith("webcal://", true) -> "https://" + u.substring(9)
            u.startsWith("http://", true) -> "https://" + u.substring(7)
            u.contains("://") -> u
            else -> "https://$u"
        }
    }

    private fun sameFeed(a: String, b: String): Boolean {
        fun key(u: String) = httpUrl(u).let { googleIcsUrl(it) ?: it }
        return key(a) == key(b)
    }

    fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: "the server"

    /**
     * Google share and embed links (…?cid=… or …?src=…) point at a web page.
     * The calendar itself is at /calendar/ical/<id>/public/basic.ics, if it's public.
     */
    private fun googleIcsUrl(url: String): String? {
        if (!hostOf(url).endsWith("google.com")) return null
        val decoded = generateSequence(url) { java.net.URLDecoder.decode(it, "UTF-8").takeIf { d -> d != it } }.last()
        val raw = Regex("""[?&](?:cid|src)=([^&\s]+)""").find(decoded)?.groupValues?.get(1) ?: return null
        val id = if ('@' in raw) raw else runCatching {
            String(java.util.Base64.getUrlDecoder().decode(raw.replace('+', '-').replace('/', '_').trimEnd('=')))
        }.getOrNull()
        if (id == null || '@' !in id) return null
        return "https://calendar.google.com/calendar/ical/" + java.net.URLEncoder.encode(id, "UTF-8") + "/public/basic.ics"
    }

    private const val GOOGLE_PRIVATE =
        "This Google calendar isn't public, so it needs its secret address. On a computer, open Google Calendar → " +
            "Settings → click the calendar → Integrate calendar → copy \"Secret address in iCal format\"."

    private data class Link(val url: String, val name: String?)

    /** Calendar links in any text, with the name written before each on its line ("Work: https://…"). */
    private fun findLinks(text: String): List<Link> {
        val pattern = Regex("""(?i)\b(?:https?|webcals?)://[^\s"'<>]+""")
        val found = text.lines().flatMap { line ->
            pattern.findAll(line).mapIndexed { i, m ->
                val before = if (i == 0) line.substring(0, m.range.first).trim() else ""
                val name = before.takeIf { it.length in 2..40 && it.last() in ":|-=" }
                    ?.trimEnd(':', '|', '-', '=', ' ')?.takeIf { it.isNotEmpty() && !it.equals("URL", true) }
                Link(m.value.trimEnd('.', ',', ')', ';'), name)
            }.toList()
        }.distinctBy { it.url }
        // A bare address typed without https://.
        if (found.isEmpty() && text.isNotBlank() && ' ' !in text.trim() && '.' in text) return listOf(Link(text.trim(), null))
        return found
    }
}

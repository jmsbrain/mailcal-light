package com.jmsbrain.mailcal

import android.content.res.Configuration
import android.util.DisplayMetrics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.math.roundToInt

/** Opens the LightOS text editor; [onDone] gets the new text (not called on back). */
fun interface TextEditor {
    fun edit(title: String, value: String, singleLine: Boolean, caps: Boolean, onDone: (String) -> Unit)
}

val LocalTextEditor = staticCompositionLocalOf<TextEditor> { error("No page") }

/** Lives as long as the page, not just while it's on screen. */
val LocalPageScope = staticCompositionLocalOf<CoroutineScope> { error("No page") }

/** The page's SDK context, for background jobs. */
val LocalLightContext = staticCompositionLocalOf<SealedLightContext> { error("No page") }

/** Opens the date and time pickers as pages, so back only closes the picker. */
interface Pickers {
    fun date(selected: LocalDate, onPick: (LocalDate) -> Unit)
    fun time(selected: LocalTime, onPick: (LocalTime) -> Unit)
}

val LocalPickers = staticCompositionLocalOf<Pickers> { error("No page") }

private val LocalPageStore = staticCompositionLocalOf<MutableMap<String, Any?>> { error("No page") }

/**
 * Like remember, but kept by the page. The SDK drops a page's composition whenever
 * another page opens on top (the text editor, for one), and this survives that.
 */
@Composable
fun <T> rememberOnPage(key: String, init: () -> T): T {
    val store = LocalPageStore.current
    @Suppress("UNCHECKED_CAST")
    return remember(store) { store.getOrPut(key, init) as T }
}

/** A Mailcal page: black background, plus the page-held state above. */
abstract class Page<R>(sealedActivity: SealedLightActivity) : SimpleLightScreen<R>(sealedActivity) {
    private val scope = MainScope()
    private val store = mutableMapOf<String, Any?>()
    private val editor = TextEditor { title, value, singleLine, caps, onDone ->
        navigateTo({ TextEditPage(it, title, value, singleLine, caps) }) { onDone(it) }
    }
    private val pickers = object : Pickers {
        override fun date(selected: LocalDate, onPick: (LocalDate) -> Unit) =
            navigateTo({ DatePickerPage(it, selected) }) { onPick(it) }

        override fun time(selected: LocalTime, onPick: (LocalTime) -> Unit) =
            navigateTo({ TimePickerPage(it, selected) }) { onPick(it) }
    }

    @Composable
    abstract fun Body()

    @Composable
    final override fun Content() {
        Box(Modifier.fillMaxSize().background(LightStyle.background).systemBarsPadding()) {
            // Every page shares this Content(), so key by page or Compose reuses the last page's state.
            key(this@Page) {
                CompositionLocalProvider(
                    LocalTextEditor provides editor,
                    LocalPageScope provides scope,
                    LocalPageStore provides store,
                    LocalLightContext provides lightContext,
                    LocalPickers provides pickers,
                ) { Body() }
            }
        }
    }

    override fun willShow() {
        // Pages also show when one above them closes; that isn't opening the app.
        if (!paused) return
        paused = false
        appOpened(lightContext)
    }

    override fun onAppPause() {
        paused = true
    }

    override fun onScreenDestroy() {
        scope.cancel()
    }

    private companion object {
        var paused = true
    }
}

private fun appOpened(ctx: SealedLightContext) {
    // Refresh on open, at most every few minutes.
    val s = Repo.state.value
    val stale = (s.updatedAt ?: 0) < System.currentTimeMillis() - 5 * 60_000
    if (s.account != null && !s.syncing && stale) Repo.refreshChanged()
    else Repo.rebuild()  // The day may have changed.
    Feeds.rebuild()
    Repo.refreshFeedsIfStale()
    Repo.scheduleSync(ctx)
}

@InitialScreen
class HomePage(sealedActivity: SealedLightActivity) : Page<Unit>(sealedActivity) {
    init {
        Repo.init(lightContext)
        Feeds.init(lightContext)
    }

    private var source by mutableStateOf(Repo.source())
    private var month by mutableStateOf(Repo.monthView())
    private var selected by mutableStateOf(LocalDate.now())

    @Composable
    override fun Body() {
        val s by Repo.state.collectAsState()
        val feeds by Feeds.state.collectAsState()
        if (s.account == null) {
            LoginScreen()
            return
        }
        AgendaScreen(
            s = s,
            onOpen = { item -> navigateTo({ EventPage(it, item) }) },
            onNew = { day -> navigateTo({ EditPage(it, null, day = day) }) },
            onCalendars = { navigateTo(::CalendarsPage) },
            feeds = feeds,
            source = source,
            onSource = { source = it; Repo.setSource(it) },
            onFeeds = { navigateTo(::FeedsPage) },
            month = month,
            onMonthChange = { month = it; Repo.setMonthView(it) },
            selected = selected,
            onSelect = { selected = it },
        )
    }
}

class EventPage(sealedActivity: SealedLightActivity, private val item: Item) : Page<Unit>(sealedActivity) {
    @Composable
    override fun Body() {
        val s by Repo.state.collectAsState()
        EventScreen(
            item = item,
            showCalendar = if (item.cal.url.startsWith(Feeds.FEED_PREFIX)) true else s.calendars.size > 1,
            onBack = { goBack() },
            // Saved: back to the agenda, since this copy of the event is now old.
            onEdit = { one -> navigateTo({ EditPage(it, item, one) }) { goBack() } },
            onDeleted = { goBack() },
        )
    }
}

/** [item] null makes a new event on [day]; [one] edits a single repeat. Returns Unit when saved. */
class EditPage(
    sealedActivity: SealedLightActivity,
    private val item: Item?,
    private val one: Boolean = false,
    private val day: LocalDate? = null,
) : Page<Unit>(sealedActivity) {
    @Composable
    override fun Body() {
        val s by Repo.state.collectAsState()
        EditScreen(s = s, item = item, one = one, day = day, onCancel = { goBack() }, onSaved = { goBack(Unit) })
    }
}

class CalendarsPage(sealedActivity: SealedLightActivity) : Page<Unit>(sealedActivity) {
    @Composable
    override fun Body() {
        val s by Repo.state.collectAsState()
        CalendarsScreen(s, onBack = { goBack() })
    }
}

class FeedsPage(sealedActivity: SealedLightActivity) : Page<Unit>(sealedActivity) {
    @Composable
    override fun Body() {
        val feeds by Feeds.state.collectAsState()
        FeedsScreen(feeds, onBack = { goBack() }, onEdit = { id -> navigateTo({ FeedEditPage(it, id) }) })
    }
}

class FeedEditPage(sealedActivity: SealedLightActivity, private val id: String) : Page<Unit>(sealedActivity) {
    @Composable
    override fun Body() {
        val feeds by Feeds.state.collectAsState()
        FeedEditScreen(id, feeds, onBack = { goBack() })
    }
}

/** The LightOS keyboard and editor. Returns the text, or nothing on back. */
class TextEditPage(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String,
    private val singleLine: Boolean,
    private val caps: Boolean,
) : SimpleLightScreen<String>(sealedActivity) {
    @Composable
    override fun Content() {
        val keyboard = rememberKeyboardOptions()
        val text = rememberTextFieldState(initial)
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            DefaultDisplaySize {
                LightTextInputEditor(
                    title = title,
                    state = text,
                    keyboardOptionsFlow = keyboard,
                    onSubmit = { goBack(it.toString()) },
                    onBack = { goBack() },
                    modifier = Modifier.background(LightThemeTokens.colors.background),
                    submitLabel = "DONE",
                    singleLine = singleLine,
                    initialCaps = caps && initial.isEmpty(),
                )
            }
        }
    }
}

/** The phone's default display size. A smaller Display size setting shrinks the SDK keyboard; LightOS's own doesn't. */
@Composable
private fun DefaultDisplaySize(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    val config = LocalConfiguration.current
    val stable = DisplayMetrics.DENSITY_DEVICE_STABLE
    val density = stable / DisplayMetrics.DENSITY_DEFAULT.toFloat()
    // Same pixels, counted in default-size dp (the SDK's grid sizes from these).
    val defaultConfig = Configuration(config).apply {
        densityDpi = stable
        screenWidthDp = (config.screenWidthDp * current.density / density).roundToInt()
        screenHeightDp = (config.screenHeightDp * current.density / density).roundToInt()
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density, current.fontScale),
        LocalConfiguration provides defaultConfig,
        content = content,
    )
}

class DatePickerPage(sealedActivity: SealedLightActivity, private val selected: LocalDate) : Page<LocalDate>(sealedActivity) {
    @Composable
    override fun Body() = DatePicker(selected, onCancel = { goBack() }, onPick = { goBack(it) })
}

class TimePickerPage(sealedActivity: SealedLightActivity, private val selected: LocalTime) : Page<LocalTime>(sealedActivity) {
    @Composable
    override fun Body() = TimePicker(selected, onCancel = { goBack() }, onPick = { goBack(it) })
}

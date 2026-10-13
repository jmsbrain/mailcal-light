package com.jmsbrain.mailcal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

/** Where the agenda's events come from. */
enum class Source { Mailbox, Feeds }

private sealed interface Row {
    val key: String
    data class Day(val day: LocalDate, val empty: Boolean) : Row { override val key = "d$day" }
    data class Event(val item: Item) : Row { override val key = "e${item.href}|${item.occ.start.toInstant()}|${item.occ.uid}" }
}

@Composable
fun AgendaScreen(
    s: UiState,
    feeds: FeedsState,
    source: Source,
    onSource: (Source) -> Unit,
    onOpen: (Item) -> Unit,
    onNew: (LocalDate?) -> Unit,
    onCalendars: () -> Unit,
    onFeeds: () -> Unit,
    month: Boolean,
    onMonthChange: (Boolean) -> Unit,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val mail = source == Source.Mailbox
    val items = if (mail) s.items else feeds.items
    val loading = if (mail) s.syncing else feeds.refreshing
    val showCalendar = if (mail) s.calendars.count { !it.hidden } > 1 else feeds.feeds.count { !it.hidden } > 1
    var confirmSignOut by remember { mutableStateOf(false) }
    val ctx = LocalLightContext.current
    var showError by remember { mutableStateOf(false) }

    val rows = remember(items, today) {
        val out = mutableListOf<Row>()
        val byDay = items.groupBy { it.occ.start.withZoneSameInstant(zone).toLocalDate() }
        val days = (byDay.keys + today).toSortedSet()
        for (d in days) {
            val list = byDay[d].orEmpty()
            out += Row.Day(d, list.isEmpty())
            list.forEach { out += Row.Event(it) }
        }
        out.distinctBy { it.key }
    }
    val todayIndex = rows.indexOfFirst { it is Row.Day && it.day == today }.coerceAtLeast(0)
    // Each source opens at today.
    val listState = remember(source) { LazyListState(todayIndex) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopBar(
            left = {
                Row {
                    TextButton("TODAY") {
                        if (month) onSelect(today) else scope.launch { listState.scrollToItem(todayIndex) }
                    }
                    HGap(24f)
                    TextButton(if (month) "LIST" else "MONTH") { onMonthChange(!month) }
                }
            },
            right = {
                Row {
                    if (mail && s.calendars.any { !it.info.readOnly }) {
                        TextButton("NEW") { onNew(if (month) selected else null) }
                        HGap(24f)
                    }
                    MenuButton(
                        "MENU",
                        if (mail) listOf(
                            "REFRESH" to { Repo.refresh() },
                            "CALENDARS" to onCalendars,
                            "SIGN OUT" to { confirmSignOut = true },
                        ) else listOf(
                            "REFRESH" to { Repo.refreshFeeds() },
                            "FEEDS" to onFeeds,
                        ),
                    )
                }
            },
        )
        // Errors are a one-line status (tap for details) so both sources keep the same layout.
        val error = when {
            loading -> null
            mail -> s.error
            feeds.feeds.any { it.error != null && !it.hidden } -> "Some feeds couldn't update. MENU → FEEDS to see why."
            else -> null
        }
        Column(Modifier.padding(horizontal = designDp(24f))) {
            SourceTabs(source, onSource) {
                when {
                    loading -> Status("Updating…")
                    error != null -> BasicText(
                        "Couldn't update",
                        style = labelStyle().copy(color = LightStyle.content),
                        modifier = Modifier.flatClickable { showError = !showError }.padding(vertical = designDp(4f)),
                    )
                    mail && s.updatedAt != null -> Status("Updated " + updatedText(s.updatedAt, zone))
                    !mail -> feeds.feeds.filter { !it.hidden }.mapNotNull { it.updatedAt }.minOrNull()
                        ?.let { Status("Updated " + updatedText(it, zone)) }
                }
            }
            when {
                confirmSignOut -> {
                    Gap(6f)
                    BasicText("Sign out? This only removes the copy on this phone; your calendar stays on mailbox.org.", style = paragraphStyle())
                    Row {
                        TextButton("SIGN OUT") { Repo.signOut(ctx) }
                        HGap(32f)
                        TextButton("CANCEL") { confirmSignOut = false }
                    }
                }
                showError && error != null -> {
                    Gap(4f)
                    BasicText(error, style = paragraphStyle(), modifier = Modifier.flatClickable { showError = false })
                }
            }
            Gap(6f)
            Box(Modifier.fillMaxWidth().height(1.dp).background(LightStyle.secondary))
        }

        // No swipe actions: sideways swipes are swallowed so they don't count as taps on a row.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> change.consume() }
            },
        ) {
            val empty = when {
                !mail && feeds.feeds.isEmpty() -> "No feeds yet. MENU → FEEDS to add a calendar link."
                loading && items.isEmpty() -> "Loading…"
                else -> "Nothing scheduled."
            }
            if (month) {
                MonthBody(items, empty, selected, onSelect, onOpen, onReselect = { onSource(if (mail) Source.Feeds else Source.Mailbox) }, showCalendar, zone)
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = designDp(24f))) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is Row.Day -> Column(
                                Modifier.fillMaxWidth()
                                    .flatClickable { if (mail) onNew(row.day) }
                                    .padding(top = designDp(18f), bottom = designDp(4f)),
                            ) {
                                BasicText(dayLabel(row.day), style = labelStyle().copy(color = if (row.day == today) LightStyle.content else LightStyle.secondary))
                                if (row.empty) {
                                    Gap(6f)
                                    Note(empty)
                                }
                            }
                            is Row.Event -> EventRow(row.item, showCalendar, zone) { onOpen(row.item) }
                        }
                    }
                    item { Gap(60f) }
                }
            }
        }
    }
}

/** MAILBOX · FEEDS, with [status] on the right. */
@Composable
private fun SourceTabs(source: Source, onSource: (Source) -> Unit, status: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Source.entries.forEachIndexed { i, src ->
            if (i > 0) BasicText("  ·  ", style = labelStyle())
            BasicText(
                src.name.uppercase(),
                style = labelStyle().copy(color = if (src == source) LightStyle.content else LightStyle.secondary),
                modifier = Modifier.flatClickable { onSource(src) }.padding(vertical = designDp(4f)),
            )
        }
        Spacer(Modifier.weight(1f))
        status()
    }
}

@Composable
private fun Status(text: String) {
    BasicText(text, style = labelStyle())
}

@Composable
fun EventRow(item: Item, showCalendar: Boolean, zone: ZoneId, onClick: () -> Unit) {
    val o = item.occ
    val past = o.end.toInstant() < Instant.now()
    val color = if (past) LightStyle.secondary else LightStyle.content
    Row(Modifier.fillMaxWidth().flatClickable(onClick).padding(vertical = designDp(7f))) {
        Column(Modifier.width(designDp(112f)).padding(top = designDp(3f))) {
            val (start, end) = timeLines(o, zone)
            BasicText(start, style = paragraphStyle().copy(color = LightStyle.secondary))
            if (end != null) BasicText(end, style = paragraphStyle().copy(color = LightStyle.secondary), maxLines = 2)
        }
        Column(Modifier.weight(1f)) {
            BasicText(o.title, style = copyStyle().copy(color = color), maxLines = 2)
            val sub = listOfNotNull(
                o.location.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() },
                item.cal.name.takeIf { showCalendar },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) BasicText(sub, style = paragraphStyle().copy(color = LightStyle.secondary), maxLines = 1)
        }
    }
}

/** Start and end for the time column, e.g. "9:00a" / "9:30a", or "all day" / "to Fri". */
private fun timeLines(o: Occurrence, zone: ZoneId): Pair<String, String?> {
    val s = o.start.withZoneSameInstant(zone)
    val e = o.end.withZoneSameInstant(zone)
    val days = java.time.temporal.ChronoUnit.DAYS.between(s.toLocalDate(), e.toLocalDate())
    fun day(d: LocalDate) = if (days < 7) d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US)
        else d.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.US))
    if (o.allDay) {
        val last = e.toLocalDate().minusDays(1)
        return "all day" to if (last > s.toLocalDate()) "to " + day(last) else null
    }
    val start = clock(s.toLocalTime())
    return when {
        e <= s -> start to null
        days == 0L -> start to clock(e.toLocalTime())
        else -> start to day(e.toLocalDate()) + " " + clock(e.toLocalTime())
    }
}

private fun updatedText(at: Long, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val time = clock(t.toLocalTime())
    return if (t.toLocalDate() == LocalDate.now(zone)) time else t.toLocalDate().toString() + " " + time
}

package com.jmsbrain.mailcal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightIcons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where the agenda's events come from. */
enum class Source { Mailbox, Feeds }

/**
 * One day at a time, like the LightOS calendar. MONTH shows the grid; picking a date shows that day.
 * < and > step a day (a month in the grid).
 */
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
    val canAdd = mail && s.calendars.any { !it.info.readOnly }
    var confirmSignOut by remember { mutableStateOf(false) }
    val ctx = LocalLightContext.current
    var showError by remember { mutableStateOf(false) }
    val byDay = remember(items) { eventsByDay(items, zone) }

    val error = when {
        loading -> null
        mail -> s.error
        feeds.feeds.any { it.error != null && !it.hidden } -> "Some feeds couldn't update. Settings → FEEDS to see why."
        else -> null
    }
    val updated = if (mail) s.updatedAt else feeds.feeds.filter { !it.hidden }.mapNotNull { it.updatedAt }.minOrNull()
    val status = when {
        loading -> "UPDATING…"
        error != null -> "COULDN'T UPDATE"
        updated != null -> "UPDATED " + updatedText(updated, zone)
        else -> "NOT UPDATED YET"
    }
    val refresh = { if (mail) Repo.refresh() else Repo.refreshFeeds() }

    Column(Modifier.fillMaxSize()) {
        // (+) on the left, TODAY and MONTH in the middle, settings on the right.
        Box(Modifier.fillMaxWidth().padding(horizontal = designDp(20f), vertical = designDp(8f))) {
            if (canAdd) Box(Modifier.align(Alignment.CenterStart)) { IconButton(LightIcons.ADD, "New event") { onNew(selected) } }
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                TextButton("TODAY") { onSelect(today) }
                HGap(28f)
                TextButton("MONTH") { onMonthChange(!month) }
            }
            Box(Modifier.align(Alignment.CenterEnd)) {
                MenuAnchor(
                    listOf(status to { if (error != null) showError = true else refresh() }, "REFRESH" to refresh) +
                        if (mail) listOf("CALENDARS" to onCalendars, "SIGN OUT" to { confirmSignOut = true })
                        else listOf("FEEDS" to onFeeds),
                ) { open -> IconButton(LightIcons.SETTINGS, "Settings", onClick = open) }
            }
        }
        Column(Modifier.padding(horizontal = designDp(24f))) {
            // < MAILBOX · FEEDS >
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.align(Alignment.CenterStart)) {
                    IconButton(LightIcons.BACK, if (month) "Previous month" else "Previous day", sizePx = 26f) {
                        onSelect(if (month) selected.minusMonths(1) else selected.minusDays(1))
                    }
                }
                SourceTabs(source, onSource, Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.CenterEnd)) {
                    IconButton(LightIcons.BACK, if (month) "Next month" else "Next day", sizePx = 26f, mirror = true) {
                        onSelect(if (month) selected.plusMonths(1) else selected.plusDays(1))
                    }
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
            if (month) {
                MonthGrid(byDay, selected, today) { date -> onSelect(date); onMonthChange(false) }
            } else {
                val empty = when {
                    !mail && feeds.feeds.isEmpty() -> "No feeds yet. Settings → FEEDS to add a calendar link."
                    loading && items.isEmpty() -> "Loading…"
                    else -> "Nothing scheduled."
                }
                val dayItems = byDay[selected].orEmpty()
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = designDp(24f))) {
                    item(key = "day") {
                        Column(
                            Modifier.fillMaxWidth()
                                .flatClickable { if (canAdd) onNew(selected) }
                                .padding(top = designDp(18f), bottom = designDp(4f)),
                        ) {
                            BasicText(dayLabel(selected), style = labelStyle().copy(color = if (selected == today) LightStyle.content else LightStyle.secondary))
                            if (dayItems.isEmpty()) {
                                Gap(6f)
                                Note(empty)
                            }
                        }
                    }
                    items(dayItems, key = { "e${it.href}|${it.occ.start.toInstant()}|${it.occ.uid}" }) { item ->
                        EventRow(item, showCalendar, zone) { onOpen(item) }
                    }
                    item { Gap(60f) }
                }
            }
        }
    }
}

/** MAILBOX · FEEDS. */
@Composable
private fun SourceTabs(source: Source, onSource: (Source) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Source.entries.forEachIndexed { i, src ->
            if (i > 0) BasicText("  ·  ", style = labelStyle())
            BasicText(
                src.name.uppercase(),
                style = labelStyle().copy(color = if (src == source) LightStyle.content else LightStyle.secondary),
                modifier = Modifier.flatClickable { onSource(src) }.padding(vertical = designDp(8f)),
            )
        }
    }
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

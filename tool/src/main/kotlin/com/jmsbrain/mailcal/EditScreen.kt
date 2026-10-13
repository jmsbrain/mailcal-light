package com.jmsbrain.mailcal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.launch

private val fieldDate = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

/** New event ([item] null), the whole event, or one repeat ([one]). */
@Composable
fun EditScreen(
    s: UiState,
    item: Item?,
    one: Boolean,
    day: LocalDate?,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    // Kept by the page so it survives the editor and pickers.
    val original = rememberOnPage("original") { initialDraft(item, one, day, zone) }
    var d by rememberOnPage("draft") { mutableStateOf(original) }
    var cal by rememberOnPage("cal") { mutableStateOf(item?.cal?.url ?: s.defaultCal ?: s.calendars.firstOrNull { !it.info.readOnly }?.info?.url) }
    var busy by rememberOnPage("busy") { mutableStateOf(false) }
    var confirmDiscard by rememberOnPage("confirmDiscard") { mutableStateOf(false) }
    var error by rememberOnPage("error") { mutableStateOf<String?>(null) }
    val scope = LocalPageScope.current
    val pickers = LocalPickers.current
    val dirty = d != original

    fun cancel() { if (dirty) confirmDiscard = true else onCancel() }

    fun save() {
        if (d.title.isBlank()) { error = "Add a title."; return }
        if (d.end < d.start) { error = "It ends before it starts."; return }
        if (item != null && !dirty) { onCancel(); return }
        busy = true
        error = null
        scope.launch {
            runCatching {
                when {
                    item == null -> Repo.create(cal ?: error("No calendar to save to."), d)
                    one -> Repo.saveOne(item, d)
                    else -> Repo.saveAll(item, d)
                }
            }.onSuccess { onSaved() }
                .onFailure {
                    busy = false
                    error = it.message ?: "Couldn't save."
                }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth()) {
            when {
                busy -> TopBar(left = { Note("Saving…") })
                confirmDiscard -> Column(Modifier.padding(horizontal = designDp(24f), vertical = designDp(12f))) {
                    BasicText("Discard your changes?", style = paragraphStyle())
                    Row {
                        TextButton("DISCARD", onClick = onCancel)
                        HGap(32f)
                        TextButton("KEEP EDITING") { confirmDiscard = false }
                    }
                }
                else -> TopBar(left = { TextButton("CANCEL") { cancel() } }, right = { TextButton("SAVE") { save() } })
            }
            error?.let {
                BasicText(it, style = paragraphStyle(), modifier = Modifier.padding(horizontal = designDp(24f)))
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = designDp(24f)),
        ) {
            if (one) {
                Note("Changes apply to this one only.")
                Gap(4f)
            } else if (item?.occ?.recurring == true) {
                Note("Changes apply to every repeat.")
                Gap(4f)
            }
            Field("TITLE", d.title, { d = d.copy(title = it); error = null })
            Field("WHERE", d.location, { d = d.copy(location = it) })
            ValueRow("ALL DAY", if (d.allDay) "Yes" else "No") { d = toggleAllDay(d) }
            Row {
                Box(Modifier.weight(1.6f)) { ValueRow("STARTS", d.start.format(fieldDate)) { pickers.date(d.start.toLocalDate()) { d = moveStart(d, d.start.with(it)); error = null } } }
                if (!d.allDay) Box(Modifier.weight(1f)) { ValueRow("", clock(d.start.toLocalTime())) { pickers.time(d.start.toLocalTime()) { d = moveStart(d, d.start.with(it)); error = null } } }
            }
            Row {
                Box(Modifier.weight(1.6f)) { ValueRow("ENDS", d.end.format(fieldDate)) { pickers.date(d.end.toLocalDate()) { d = d.copy(end = d.end.with(it)); error = null } } }
                if (!d.allDay) Box(Modifier.weight(1f)) { ValueRow("", clock(d.end.toLocalTime())) { pickers.time(d.end.toLocalTime()) { d = d.copy(end = d.end.with(it)); error = null } } }
            }
            if (!one) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ValueRow("REPEATS", d.repeat.label.lowercase().replaceFirstChar { it.uppercase() }, null) }
                    MenuButton(
                        "CHANGE",
                        Repeat.entries.filter { it != Repeat.Custom || original.repeat == Repeat.Custom }
                            .map { r -> r.label to { d = d.copy(repeat = r) } },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ValueRow("REMINDER", reminderText(d.reminder), null) }
                MenuButton(
                    "CHANGE",
                    Reminder.entries.filter { it != Reminder.Custom || original.reminder == Reminder.Custom }
                        .map { r -> r.label to { d = d.copy(reminder = r) } },
                )
            }
            if (d.reminder != Reminder.None) {
                Note("Saved with the event, so your other devices remind you. This phone can't show reminders yet.")
                Gap(4f)
            }
            if (item == null) {
                val writable = s.calendars.filter { !it.info.readOnly }
                if (writable.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { ValueRow("CALENDAR", writable.firstOrNull { it.info.url == cal }?.info?.name ?: "", null) }
                        MenuButton("CHANGE", writable.map { c -> c.info.name.uppercase() to { cal = c.info.url } })
                    }
                }
            }
            Field("NOTES", d.notes, { d = d.copy(notes = it) }, singleLine = false)
            Gap(60f)
        }
    }
}

private fun initialDraft(item: Item?, one: Boolean, day: LocalDate?, zone: ZoneId): Draft {
    if (item == null) {
        val now = LocalDateTime.now(zone)
        val start = if (day == null || day == now.toLocalDate()) now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        else day.atTime(9, 0)
        return Draft("", "", "", false, start, start.plusHours(1), Repeat.None, Reminder.None)
    }
    val o = item.occ
    val start = o.start.withZoneSameInstant(zone).toLocalDateTime()
    var end = o.end.withZoneSameInstant(zone).toLocalDateTime()
    if (o.allDay) end = end.minusDays(1).coerceAtLeast(start)
    val repeat = if (one) Repeat.None else VCal.repeatOf(Repo.resource(item)?.ics?.let { VCal.rruleOf(it, o.uid) })
    val reminder = Repo.resource(item)?.ics?.let { VCal.reminderOf(it, o, one, zone) } ?: Reminder.Custom
    return Draft(o.title.takeIf { it != "(No title)" } ?: "", o.location, o.description, o.allDay, start, end, repeat, reminder)
}

/** "30 min before", "None". */
fun reminderText(r: Reminder) = when (r) {
    Reminder.AtStart -> "At time of event"
    Reminder.Custom -> "Custom (kept)"
    else -> r.label.lowercase().replaceFirstChar { it.uppercase() }
}

/** Moves the start and keeps the length. */
private fun moveStart(d: Draft, start: LocalDateTime): Draft {
    val length = Duration.between(d.start, d.end).coerceAtLeast(Duration.ZERO)
    return d.copy(start = start, end = start.plus(length))
}

private fun toggleAllDay(d: Draft): Draft =
    if (d.allDay) {
        val start = d.start.toLocalDate().atTime(9, 0)
        d.copy(allDay = false, start = start, end = start.plusHours(1))
    } else {
        d.copy(allDay = true, start = d.start.toLocalDate().atStartOfDay(), end = d.end.toLocalDate().atStartOfDay().coerceAtLeast(d.start.toLocalDate().atStartOfDay()))
    }

// ---- pickers ----

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

@Composable
fun DatePicker(selected: LocalDate, onCancel: () -> Unit, onPick: (LocalDate) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize()) {
        TopBar(left = { TextButton("CANCEL", onClick = onCancel) }, right = { TextButton("TODAY") { onPick(today) } })
        Column(Modifier.padding(horizontal = designDp(24f))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton("PREV") { month = month.minusMonths(1) }
                BasicText(
                    month.format(monthFmt).uppercase(),
                    style = buttonStyle().copy(textAlign = TextAlign.Center),
                    modifier = Modifier.weight(1f),
                )
                TextButton("NEXT") { month = month.plusMonths(1) }
            }
            Gap(12f)
            Row {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                    BasicText(it, style = labelStyle().copy(textAlign = TextAlign.Center), modifier = Modifier.weight(1f))
                }
            }
            Gap(6f)
            val first = month.atDay(1)
            val lead = first.dayOfWeek.value - 1
            val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
            cells.chunked(7).forEach { week ->
                Row {
                    for (i in 0 until 7) {
                        val date = week.getOrNull(i)
                        Box(
                            Modifier
                                .weight(1f)
                                .let { if (date == selected) it.background(LightStyle.content) else it }
                                .let { if (date != null) it.flatClickable { onPick(date) } else it }
                                .padding(vertical = designDp(10f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (date != null) BasicText(
                                date.dayOfMonth.toString(),
                                style = paragraphStyle().copy(
                                    color = if (date == selected) LightStyle.background else LightStyle.content,
                                    textDecoration = if (date == today) TextDecoration.Underline else TextDecoration.None,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimePicker(selected: LocalTime, onCancel: () -> Unit, onPick: (LocalTime) -> Unit) {
    val times = remember(selected) {
        val t = selected.withSecond(0).withNano(0)
        ((0 until 96).map { LocalTime.of(it / 4, (it % 4) * 15) } + t).distinct().sorted()
    }
    val index = times.indexOf(selected.withSecond(0).withNano(0))
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (index - 3).coerceAtLeast(0))
    Column(Modifier.fillMaxSize()) {
        TopBar(left = { TextButton("CANCEL", onClick = onCancel) })
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(horizontal = designDp(24f))) {
            itemsIndexed(times) { i, t ->
                val sel = i == index
                BasicText(
                    clock(t),
                    style = copyStyle().copy(color = if (sel) LightStyle.background else LightStyle.content),
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (sel) it.background(LightStyle.content) else it }
                        .flatClickable { onPick(t) }
                        .padding(horizontal = designDp(8f), vertical = designDp(6f)),
                )
            }
        }
    }
}

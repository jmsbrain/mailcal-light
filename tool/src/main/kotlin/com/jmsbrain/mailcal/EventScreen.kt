package com.jmsbrain.mailcal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

private enum class Mode { View, AskEdit, AskDelete, Busy }

@Composable
fun EventScreen(
    item: Item,
    showCalendar: Boolean,
    onBack: () -> Unit,
    onEdit: (one: Boolean) -> Unit,
    onDeleted: () -> Unit,
) {
    val o = item.occ
    val zone = ZoneId.systemDefault()
    var mode by remember { mutableStateOf(Mode.View) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val canEdit = !item.cal.readOnly

    fun delete(one: Boolean) {
        mode = Mode.Busy
        error = null
        scope.launch {
            runCatching { if (one) Repo.deleteOne(item) else Repo.deleteAll(item) }
                .onSuccess { onDeleted() }
                .onFailure {
                    error = it.message ?: "Couldn't delete this event."
                    mode = Mode.View
                }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            left = { TextButton("BACK", enabled = mode != Mode.Busy, onClick = onBack) },
            right = {
                if (canEdit && mode == Mode.View) TextButton("EDIT") { if (o.recurring) mode = Mode.AskEdit else onEdit(false) }
            },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = designDp(24f)),
        ) {
            if (mode == Mode.AskEdit) {
                BasicText("This event repeats. Change just this one, or all of them?", style = paragraphStyle())
                Row {
                    TextButton("THIS ONE") { mode = Mode.View; onEdit(true) }
                    HGap(28f)
                    TextButton("ALL") { mode = Mode.View; onEdit(false) }
                    HGap(28f)
                    TextButton("CANCEL") { mode = Mode.View }
                }
                Gap(20f)
            }
            BasicText(o.title, style = headingStyle())
            Gap(12f)
            BasicText(whenText(o, zone), style = paragraphStyle())
            if (o.recurring) {
                Gap(4f)
                Note(repeatText(o.rrule))
            }
            val isFeed = item.cal.url.startsWith(Feeds.FEED_PREFIX)
            if (showCalendar) Section(if (isFeed) "FEED" else "CALENDAR", item.cal.name + if (item.cal.readOnly && !isFeed) " (read only)" else "")
            if (o.location.isNotBlank()) Section("WHERE", o.location)
            val reminder = remember(item) { Repo.resource(item)?.ics?.let { VCal.reminderOf(it, o, true, zone) } }
            if (reminder != null && reminder != Reminder.None) {
                Section("REMINDER", if (reminder == Reminder.Custom) "Custom" else reminderText(reminder))
            }
            if (o.description.isNotBlank()) Section("NOTES", o.description)

            error?.let {
                Gap(20f)
                BasicText(it, style = paragraphStyle())
            }
            if (canEdit) {
                Gap(40f)
                when (mode) {
                    Mode.Busy -> Note("Working…")
                    Mode.AskDelete -> {
                        BasicText(
                            if (o.recurring) "Delete just this one, or every repeat?" else "Delete this event from mailbox.org?",
                            style = paragraphStyle(),
                        )
                        Row {
                            if (o.recurring) {
                                TextButton("THIS ONE") { delete(true) }
                                HGap(28f)
                                TextButton("ALL") { delete(false) }
                            } else TextButton("DELETE") { delete(false) }
                            HGap(28f)
                            TextButton("KEEP") { mode = Mode.View }
                        }
                    }
                    else -> TextButton("DELETE EVENT") { error = null; mode = Mode.AskDelete }
                }
            }
            Gap(40f)
        }
    }
}

@Composable
private fun Section(label: String, text: String) {
    Gap(24f)
    BasicText(label, style = labelStyle())
    Gap(4f)
    BasicText(text, style = paragraphStyle())
}

/** "Repeats every 2 weeks on Mon, Wed". */
fun repeatText(r: RRule?): String {
    if (r == null) return "Repeats"
    val unit = when (r.freq) {
        "DAILY" -> "day"; "WEEKLY" -> "week"; "MONTHLY" -> "month"; "YEARLY" -> "year"
        "HOURLY" -> "hour"; "MINUTELY" -> "minute"; else -> return "Repeats"
    }
    val every = if (r.interval == 1) "every $unit" else "every ${r.interval} ${unit}s"
    val days = r.byDay.takeIf { it.isNotEmpty() }?.joinToString(", ") { (n, d) -> nth(n) + short(d) }
    val until = when {
        r.count != null -> ", ${r.count} times"
        r.until != null -> ", until " + r.until.date.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
        else -> ""
    }
    return "Repeats $every" + (days?.let { " on $it" } ?: "") + until
}

private fun short(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.US)

private fun nth(n: Int?) = when (n) {
    null -> ""
    -1 -> "last "
    1 -> "1st "; 2 -> "2nd "; 3 -> "3rd "
    else -> "${n}th "
}

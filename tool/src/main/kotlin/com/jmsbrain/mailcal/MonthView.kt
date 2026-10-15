package com.jmsbrain.mailcal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

/** Month grid with a dot per event (up to 3). Tapping a date picks it; < and > above change the month. */
@Composable
fun MonthGrid(byDay: Map<LocalDate, List<Item>>, selected: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    val month = YearMonth.from(selected)
    Column(Modifier.fillMaxSize().padding(horizontal = designDp(24f))) {
        Gap(10f)
        BasicText(
            month.format(monthFmt).uppercase(),
            style = buttonStyle().copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(),
        )
        Gap(10f)
        Row {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                BasicText(it, style = labelStyle().copy(textAlign = TextAlign.Center), modifier = Modifier.weight(1f))
            }
        }
        Gap(4f)
        val lead = month.atDay(1).dayOfWeek.value - 1
        val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row {
                for (i in 0 until 7) {
                    val date = week.getOrNull(i)
                    Box(Modifier.weight(1f)) {
                        if (date != null) DayCell(date, date == selected, date == today, byDay[date]?.size ?: 0) { onPick(date) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: Boolean, today: Boolean, count: Int, onClick: () -> Unit) {
    val fg = if (selected) LightStyle.background else LightStyle.content
    Column(
        Modifier
            .fillMaxWidth()
            .let { if (selected) it.background(LightStyle.content) else it }
            .flatClickable(onClick)
            .padding(vertical = designDp(5f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            date.dayOfMonth.toString(),
            style = paragraphStyle().copy(
                color = fg,
                textAlign = TextAlign.Center,
                textDecoration = if (today) TextDecoration.Underline else TextDecoration.None,
            ),
        )
        Row(
            Modifier.height(designDp(8f)),
            horizontalArrangement = Arrangement.spacedBy(designDp(3f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(count.coerceAtMost(3)) {
                Box(Modifier.size(designDp(5f)).background(fg, CircleShape))
            }
        }
    }
}

/** Each event under every day it covers (multi-day events too), in agenda order. */
fun eventsByDay(items: List<Item>, zone: ZoneId): Map<LocalDate, List<Item>> {
    val map = mutableMapOf<LocalDate, MutableList<Item>>()
    for (item in items) {
        val start = item.occ.start.withZoneSameInstant(zone)
        val end = item.occ.end.withZoneSameInstant(zone)
        val first = start.toLocalDate()
        // The end is exclusive: an event ending at midnight doesn't count that day.
        var last = if (end > start && end.toLocalTime() == java.time.LocalTime.MIDNIGHT) end.toLocalDate().minusDays(1) else end.toLocalDate()
        if (last < first) last = first
        var d = first
        var guard = 0
        while (d <= last && guard++ < 400) {
            map.getOrPut(d) { mutableListOf() } += item
            d = d.plusDays(1)
        }
    }
    return map
}

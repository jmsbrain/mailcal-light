package com.jmsbrain.mailcal

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)
private val shortDayFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val yearFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

/** "TODAY · TUE, OCT 14", "TOMORROW · …", or "WEDNESDAY, OCT 15". */
fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "TODAY · " + day.format(shortDayFmt)
        today.plusDays(1) -> "TOMORROW · " + day.format(shortDayFmt)
        else -> day.format(dayFmt)
    }.uppercase()
}

/** 9:00a, 12:30p */
fun clock(t: LocalTime): String {
    val h = if (t.hour % 12 == 0) 12 else t.hour % 12
    return "%d:%02d%s".format(h, t.minute, if (t.hour < 12) "a" else "p")
}

private fun date(d: LocalDate) = d.format(if (d.year == LocalDate.now().year) shortDayFmt else yearFmt)

/** When an event happens, in the phone's time zone. */
fun whenText(e: Occurrence, zone: ZoneId): String {
    if (e.allDay) {
        val last = e.end.toLocalDate().minusDays(1)
        return if (last <= e.start.toLocalDate()) date(e.start.toLocalDate()) + ", all day"
        else date(e.start.toLocalDate()) + " – " + date(last)
    }
    val s = e.start.withZoneSameInstant(zone)
    val end = e.end.withZoneSameInstant(zone)
    return when {
        end <= s -> date(s.toLocalDate()) + ", " + clock(s.toLocalTime())
        end.toLocalDate() == s.toLocalDate() -> date(s.toLocalDate()) + ", " + clock(s.toLocalTime()) + " – " + clock(end.toLocalTime())
        else -> date(s.toLocalDate()) + ", " + clock(s.toLocalTime()) + " – " + date(end.toLocalDate()) + ", " + clock(end.toLocalTime())
    }
}

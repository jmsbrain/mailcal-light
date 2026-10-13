package com.jmsbrain.mailcal

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VCalTest {
    private val ny = ZoneId.of("America/New_York")
    private val from = LocalDate.of(2026, 10, 1).atStartOfDay(ny)
    private val to = LocalDate.of(2026, 12, 31).atStartOfDay(ny)

    private val weekly = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        UID:abc
        SUMMARY:Standup
        DTSTART;TZID=America/New_York:20261005T090000
        DTEND;TZID=America/New_York:20261005T093000
        RRULE:FREQ=WEEKLY
        ATTENDEE;CN="Pat, Q":mailto:pat@example.com
        BEGIN:VALARM
        TRIGGER:-PT10M
        ACTION:DISPLAY
        END:VALARM
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    private fun occ(text: String) = Ics.expand(Ics.parse(text), from, to, ny)

    private fun draftOf(o: Occurrence, f: (Draft) -> Draft) = f(
        Draft(o.title, o.location, o.description, o.allDay, o.start.toLocalDateTime(), o.end.toLocalDateTime(), Repeat.Weekly),
    )

    @Test fun createRoundTrips() {
        val d = Draft("Lunch, with \"Sam\"; ok", "Cafe", "line1\nline2", false,
            LocalDateTime.of(2026, 10, 14, 12, 0), LocalDateTime.of(2026, 10, 14, 13, 0), Repeat.None)
        val (text, uid) = VCal.create(d, ny)
        assertTrue(text.contains("BEGIN:VTIMEZONE"))
        val o = occ(text).single()
        assertEquals(uid, o.uid)
        assertEquals("Lunch, with \"Sam\"; ok", o.title)
        assertEquals("line1\nline2", o.description)
        assertEquals(12, o.start.hour)
        assertFalse(o.recurring)
    }

    @Test fun allDayCreate() {
        val d = Draft("Trip", "", "", true, LocalDate.of(2026, 10, 14).atStartOfDay(), LocalDate.of(2026, 10, 16).atStartOfDay(), Repeat.None)
        val o = occ(VCal.create(d, ny).first).single()
        assertTrue(o.allDay)
        assertEquals(LocalDate.of(2026, 10, 17), o.end.toLocalDate())
    }

    @Test fun editOneMakesOverride() {
        val all = occ(weekly)
        val third = all[2]
        val text = VCal.updateOne(weekly, third, draftOf(third) { it.copy(title = "Moved", start = it.start.plusHours(2), end = it.end.plusHours(2)) }, ny)
        val after = occ(text)
        assertEquals(all.size, after.size)
        val moved = after.single { it.title == "Moved" }
        assertEquals(11, moved.start.hour)
        assertEquals(third.start.toLocalDate(), moved.start.toLocalDate())
        assertTrue(text.contains("ATTENDEE;CN=\"Pat, Q\":mailto:pat@example.com"))
        assertEquals(2, Regex("BEGIN:VALARM").findAll(text).count())
        // Editing the override again changes it in place.
        val again = VCal.updateOne(text, moved, draftOf(moved) { it.copy(title = "Moved twice") }, ny)
        assertEquals(1, occ(again).count { it.title == "Moved twice" })
        assertEquals(all.size, occ(again).size)
    }

    @Test fun deleteOneSkips() {
        val all = occ(weekly)
        val text = VCal.deleteOne(weekly, all[1], ny)
        val after = occ(text)
        assertEquals(all.size - 1, after.size)
        assertFalse(after.any { it.start == all[1].start })
    }

    @Test fun editAllShiftsSeries() {
        val all = occ(weekly)
        val second = all[1]
        // Move the 2nd occurrence a day later and an hour earlier: every repeat moves.
        val text = VCal.updateAll(weekly, second, draftOf(second) { it.copy(start = it.start.plusDays(1).minusHours(1), end = it.end.plusDays(1).minusHours(1)) }, ny)
        val after = occ(text)
        assertTrue(after.all { it.start.dayOfWeek == java.time.DayOfWeek.TUESDAY && it.start.hour == 8 })
        assertEquals(LocalDate.of(2026, 10, 6), after.first().start.toLocalDate())
    }

    @Test fun stopRepeating() {
        val all = occ(weekly)
        val text = VCal.updateAll(weekly, all[0], draftOf(all[0]) { it.copy(repeat = Repeat.None) }, ny)
        assertEquals(1, occ(text).size)
    }

    @Test fun newEventReminder() {
        val d = Draft("Call", "", "", false, LocalDateTime.of(2026, 10, 14, 12, 0), LocalDateTime.of(2026, 10, 14, 13, 0),
            Repeat.None, Reminder.Min30)
        val text = VCal.create(d, ny).first
        assertTrue(text.contains("TRIGGER:-PT30M"))
        val o = occ(text).single()
        assertEquals(listOf(o.start.minusMinutes(30).toInstant()), o.alarms)
        assertEquals(Reminder.Min30, VCal.reminderOf(text, o, false, ny))
        val none = VCal.create(d.copy(reminder = Reminder.None), ny).first
        assertFalse(none.contains("VALARM"))
    }

    @Test fun reminderKeptUnlessChanged() {
        val all = occ(weekly)
        // 10 minutes isn't a menu choice: it reads as Custom and survives edits.
        assertEquals(Reminder.Custom, VCal.reminderOf(weekly, all[0], false, ny))
        val kept = VCal.updateAll(weekly, all[0], draftOf(all[0]) { it.copy(title = "x", reminder = Reminder.Custom) }, ny)
        assertTrue(kept.contains("TRIGGER:-PT10M"))
        val changed = VCal.updateAll(weekly, all[0], draftOf(all[0]) { it.copy(reminder = Reminder.Day1) }, ny)
        assertFalse(changed.contains("-PT10M"))
        assertTrue(changed.contains("TRIGGER:-P1D"))
        assertEquals(1, Regex("BEGIN:VALARM").findAll(changed).count())
        val removed = VCal.updateAll(weekly, all[0], draftOf(all[0]) { it.copy(reminder = Reminder.None) }, ny)
        assertFalse(removed.contains("VALARM"))
    }

    @Test fun repeatOf() {
        assertEquals(Repeat.Weekly, VCal.repeatOf("FREQ=WEEKLY"))
        assertEquals(Repeat.Custom, VCal.repeatOf("FREQ=WEEKLY;BYDAY=MO,WE"))
        assertEquals(Repeat.None, VCal.repeatOf(null))
    }

    @Test fun longLinesFold() {
        val d = Draft("x".repeat(200), "", "é".repeat(100), false,
            LocalDateTime.of(2026, 10, 14, 12, 0), LocalDateTime.of(2026, 10, 14, 13, 0), Repeat.None)
        val text = VCal.create(d, ny).first
        assertTrue(text.split("\r\n").all { it.toByteArray().size <= 75 })
        assertEquals("x".repeat(200), occ(text).single().title)
    }
}

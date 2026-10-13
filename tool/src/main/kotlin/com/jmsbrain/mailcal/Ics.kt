package com.jmsbrain.mailcal

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A date (all-day when [time] is null) or a date-time; [zone] null means floating. */
data class IcsTime(val date: LocalDate, val time: LocalTime?, val zone: ZoneId?) {
    val allDay get() = time == null
    fun local(): LocalDateTime = date.atTime(time ?: LocalTime.MIDNIGHT)
    fun zoned(default: ZoneId): ZonedDateTime = local().atZone(zone ?: default)
}

data class IcsEvent(
    val uid: String,
    val summary: String,
    val location: String,
    val description: String,
    val start: IcsTime,
    val end: IcsTime?,
    val duration: Duration?,
    val rrule: RRule?,
    val rdates: List<IcsTime>,
    val exdates: List<IcsTime>,
    val recurrenceId: IcsTime?,
    val cancelled: Boolean,
    val alarms: List<Alarm> = emptyList(),
)

/** A VALARM trigger: [offset] from the start (or end), or a fixed time [at]. */
data class Alarm(val offset: Duration?, val fromEnd: Boolean, val at: java.time.Instant?)

data class RRule(
    val freq: String,
    val interval: Int,
    val count: Int?,
    val until: IcsTime?,
    val byDay: List<Pair<Int?, DayOfWeek>>,
    val byMonthDay: List<Int>,
    val byMonth: List<Int>,
    val bySetPos: List<Int>,
)

/** One event as it lands on the calendar. */
data class Occurrence(
    val uid: String,
    val title: String,
    val location: String,
    val description: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean,
    /** Part of a repeating series. */
    val recurring: Boolean = false,
    /** Where the series put this one (its RECURRENCE-ID), for repeats. */
    val originalStart: ZonedDateTime? = null,
    val rrule: RRule? = null,
    /** When reminders go off. */
    val alarms: List<java.time.Instant> = emptyList(),
)

object Ics {

    fun parse(text: String): List<IcsEvent> {
        val events = mutableListOf<IcsEvent>()
        val stack = ArrayDeque<String>()
        var props = mutableListOf<Prop>()
        var alarms = mutableListOf<Alarm>()
        for (line in unfold(text)) {
            val p = parseLine(line) ?: continue
            when (p.name) {
                "BEGIN" -> {
                    stack.addLast(p.value.uppercase())
                    if (p.value.equals("VEVENT", true)) { props = mutableListOf(); alarms = mutableListOf() }
                }
                "END" -> {
                    if (p.value.equals("VEVENT", true) && stack.lastOrNull() == "VEVENT") {
                        toEvent(props)?.copy(alarms = alarms)?.let(events::add)
                    }
                    stack.removeLastOrNull()
                }
                else -> when {
                    stack.lastOrNull() == "VEVENT" -> props += p
                    // Reminder triggers inside the event.
                    p.name == "TRIGGER" && stack.lastOrNull() == "VALARM" && stack.getOrNull(stack.size - 2) == "VEVENT" ->
                        alarm(p)?.let(alarms::add)
                }
            }
        }
        return events
    }

    /** The calendar's own name (X-WR-CALNAME), if it has one. */
    fun calendarName(text: String): String? =
        unfold(text).firstNotNullOfOrNull { line ->
            parseLine(line)?.takeIf { it.name == "X-WR-CALNAME" }?.let { text(it.value) }?.takeIf { it.isNotBlank() }
        }

    /** Every occurrence overlapping [from, to), with overrides and cancellations applied. */
    fun expand(events: List<IcsEvent>, from: ZonedDateTime, to: ZonedDateTime, zone: ZoneId): List<Occurrence> {
        val overrides = events.filter { it.recurrenceId != null }
        val out = mutableListOf<Occurrence>()
        for (e in events) {
            if (e.recurrenceId != null || e.cancelled) continue
            val moved = overrides.filter { it.uid == e.uid }.map { it.recurrenceId!!.zoned(zone).toInstant() }.toSet()
            val skipped = e.exdates.map { it.zoned(zone).toInstant() }.toSet()
            for (start in starts(e, to, zone)) {
                val instant = start.toInstant()
                if (instant in moved || instant in skipped) continue
                occurrence(e, start, zone, series = e.rrule != null || e.rdates.isNotEmpty()).takeIf { it.end > from && it.start < to }?.let(out::add)
            }
        }
        for (o in overrides) {
            if (o.cancelled) continue
            val master = events.firstOrNull { it.uid == o.uid && it.recurrenceId == null }
            occurrence(o, o.start.zoned(zone), zone, series = true, original = o.recurrenceId!!.zoned(zone), rule = master?.rrule).takeIf { it.end > from && it.start < to }?.let(out::add)
        }
        return out.sortedWith(compareBy({ it.start }, { !it.allDay }, { it.title }))
    }

    private fun occurrence(
        e: IcsEvent,
        start: ZonedDateTime,
        zone: ZoneId,
        series: Boolean = false,
        original: ZonedDateTime? = null,
        rule: RRule? = e.rrule,
    ): Occurrence {
        val base = e.start.zoned(zone)
        val end = when {
            e.end != null && e.start.allDay ->
                start.plusDays(java.time.temporal.ChronoUnit.DAYS.between(e.start.date, e.end.date).coerceAtLeast(1))
            e.end != null -> start.plus(Duration.between(base, e.end.zoned(zone)))
            e.duration != null -> start.plus(e.duration)
            e.start.allDay -> start.plusDays(1)
            else -> start
        }
        return Occurrence(
            e.uid, e.summary.ifBlank { "(No title)" }, e.location, e.description, start, end, e.start.allDay,
            recurring = series, originalStart = if (series) original ?: start else null, rrule = rule,
            alarms = e.alarms.mapNotNull { a ->
                a.at ?: a.offset?.let { (if (a.fromEnd) end else start).plus(it).toInstant() }
            },
        )
    }

    // Start times of the series up to [to], in the event's own zone so DST keeps the wall-clock time.
    private fun starts(e: IcsEvent, to: ZonedDateTime, zone: ZoneId): List<ZonedDateTime> {
        val evZone = e.start.zone ?: zone
        val first = e.start.local()
        val result = sortedSetOf(first)
        e.rdates.forEach { result += it.zoned(zone).withZoneSameInstant(evZone).toLocalDateTime() }
        val rule = e.rrule ?: return result.map { it.atZone(evZone) }
        val until = rule.until?.let { if (it.allDay) it.date.atTime(LocalTime.MAX).atZone(evZone).toInstant() else it.zoned(evZone).toInstant() }
        val stop = to.toInstant()
        var count = 1
        var period = 0
        while (period < 100_000) {
            val dates = periodDates(rule, first.toLocalDate(), period++) ?: break
            for (d in dates) {
                val t = d.atTime(first.toLocalTime())
                if (t <= first) continue
                val instant = t.atZone(evZone).toInstant()
                if (until != null && instant > until) return result.map { it.atZone(evZone) }
                if (rule.count != null && count >= rule.count) return result.map { it.atZone(evZone) }
                if (instant >= stop) return result.map { it.atZone(evZone) }
                result += t
                count++
            }
        }
        return result.map { it.atZone(evZone) }
    }

    // Candidate dates for the nth period of the rule, or null if the frequency isn't supported.
    private fun periodDates(r: RRule, start: LocalDate, n: Int): List<LocalDate>? {
        val step = n.toLong() * r.interval
        val days: List<LocalDate> = when (r.freq) {
            "DAILY" -> listOf(start.plusDays(step))
                .filter { r.byDay.isEmpty() || r.byDay.any { (_, dow) -> dow == it.dayOfWeek } }
                .filter { r.byMonthDay.isEmpty() || monthDayMatches(it, r.byMonthDay) }
            "WEEKLY" -> {
                val monday = start.minusDays(start.dayOfWeek.value - 1L).plusWeeks(step)
                if (r.byDay.isEmpty()) listOf(monday.plusDays(start.dayOfWeek.value - 1L))
                else r.byDay.map { (_, dow) -> monday.plusDays(dow.value - 1L) }
            }
            "MONTHLY" -> monthDates(r, start, start.withDayOfMonth(1).plusMonths(step))
            "YEARLY" -> {
                val year = start.year + step.toInt()
                when {
                    r.byMonth.isNotEmpty() -> r.byMonth.flatMap { monthDates(r, start, LocalDate.of(year, it, 1)) }
                    r.byMonthDay.isNotEmpty() -> (1..12).flatMap { monthDates(r, start, LocalDate.of(year, it, 1)) }
                    r.byDay.isNotEmpty() -> nthInRange(r.byDay, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
                    else -> listOfNotNull(runCatching { LocalDate.of(year, start.month, start.dayOfMonth) }.getOrNull())
                }
            }
            else -> return null
        }
        var sorted = days.filter { r.byMonth.isEmpty() || it.monthValue in r.byMonth }.distinct().sorted()
        if (r.bySetPos.isNotEmpty()) {
            sorted = r.bySetPos.mapNotNull { pos -> sorted.getOrNull(if (pos > 0) pos - 1 else sorted.size + pos) }.sorted()
        }
        return sorted
    }

    private fun monthDates(r: RRule, start: LocalDate, month: LocalDate): List<LocalDate> {
        val last = month.lengthOfMonth()
        val byMonthDay = r.byMonthDay.mapNotNull { d ->
            val day = if (d > 0) d else last + d + 1
            if (day in 1..last) month.withDayOfMonth(day) else null
        }
        val byDay = nthInRange(r.byDay, month, month.withDayOfMonth(last))
        return when {
            r.byMonthDay.isNotEmpty() && r.byDay.isNotEmpty() -> byMonthDay.filter { it in byDay }
            r.byMonthDay.isNotEmpty() -> byMonthDay
            r.byDay.isNotEmpty() -> byDay
            start.dayOfMonth <= last -> listOf(month.withDayOfMonth(start.dayOfMonth))
            else -> emptyList()
        }
    }

    // BYDAY inside a month or year: "TU" is every Tuesday, "2TU" the second, "-1FR" the last Friday.
    private fun nthInRange(byDay: List<Pair<Int?, DayOfWeek>>, from: LocalDate, to: LocalDate): List<LocalDate> =
        byDay.flatMap { (nth, dow) ->
            val all = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.filter { it.dayOfWeek == dow }.toList()
            when {
                nth == null -> all
                nth > 0 -> listOfNotNull(all.getOrNull(nth - 1))
                else -> listOfNotNull(all.getOrNull(all.size + nth))
            }
        }

    private fun monthDayMatches(d: LocalDate, byMonthDay: List<Int>) =
        byMonthDay.any { if (it > 0) d.dayOfMonth == it else d.dayOfMonth == d.lengthOfMonth() + it + 1 }

    // ---- reading the file ----

    private data class Prop(val name: String, val params: Map<String, String>, val value: String)

    private fun unfold(text: String): List<String> {
        val lines = mutableListOf<String>()
        for (raw in text.split("\r\n", "\n", "\r")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) lines[lines.lastIndex] += raw.substring(1)
            else if (raw.isNotEmpty()) lines += raw
        }
        return lines
    }

    private fun parseLine(line: String): Prop? {
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) { colon = i; break }
            }
        }
        if (colon < 0) return null
        val parts = line.substring(0, colon).split(';')
        val params = parts.drop(1).mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) null else it.substring(0, eq).uppercase() to it.substring(eq + 1).trim('"')
        }.toMap()
        return Prop(parts[0].uppercase(), params, line.substring(colon + 1))
    }

    private fun toEvent(props: List<Prop>): IcsEvent? {
        fun one(name: String) = props.firstOrNull { it.name == name }
        val start = one("DTSTART")?.let { time(it.value, it.params) } ?: return null
        return IcsEvent(
            uid = one("UID")?.value.orEmpty(),
            summary = text(one("SUMMARY")?.value),
            location = text(one("LOCATION")?.value),
            description = text(one("DESCRIPTION")?.value),
            start = start,
            end = one("DTEND")?.let { time(it.value, it.params) },
            duration = one("DURATION")?.value?.let(::duration),
            rrule = one("RRULE")?.value?.let(::rrule),
            rdates = props.filter { it.name == "RDATE" }.flatMap { p -> p.value.split(',').mapNotNull { time(it, p.params) } },
            exdates = props.filter { it.name == "EXDATE" }.flatMap { p -> p.value.split(',').mapNotNull { time(it, p.params) } },
            recurrenceId = one("RECURRENCE-ID")?.let { time(it.value, it.params) },
            cancelled = one("STATUS")?.value.equals("CANCELLED", true),
        )
    }

    fun text(v: String?): String {
        if (v == null) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                sb.append(if (v[i + 1] == 'n' || v[i + 1] == 'N') '\n' else v[i + 1])
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString().trim()
    }

    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val timeFmt = DateTimeFormatter.ofPattern("HHmmss")

    fun time(raw: String, params: Map<String, String>): IcsTime? = runCatching {
        val v = raw.trim()
        val date = LocalDate.parse(v.take(8), dateFmt)
        if (v.length == 8 || params["VALUE"].equals("DATE", true)) return@runCatching IcsTime(date, null, null)
        val time = LocalTime.parse(v.substring(9, 15), timeFmt)
        val zone = when {
            v.endsWith("Z") -> ZoneOffset.UTC
            params["TZID"] != null -> zone(params["TZID"]!!)
            else -> null
        }
        IcsTime(date, time, zone)
    }.getOrNull()

    /** TZID to a zone: IANA names, prefixed ones like /mozilla.org/.../America/New_York, and common Windows names. */
    fun zone(tzid: String): ZoneId? {
        val id = tzid.trim()
        runCatching { return ZoneId.of(id) }
        WINDOWS_ZONES[id]?.let { return ZoneId.of(it) }
        Regex("[A-Za-z]+/[A-Za-z_]+(/[A-Za-z_]+)?$").find(id)?.value?.let { tail -> runCatching { return ZoneId.of(tail) } }
        return null
    }

    private val WINDOWS_ZONES = mapOf(
        "Eastern Standard Time" to "America/New_York",
        "Central Standard Time" to "America/Chicago",
        "Mountain Standard Time" to "America/Denver",
        "US Mountain Standard Time" to "America/Phoenix",
        "Pacific Standard Time" to "America/Los_Angeles",
        "Alaskan Standard Time" to "America/Anchorage",
        "Hawaiian Standard Time" to "Pacific/Honolulu",
        "Atlantic Standard Time" to "America/Halifax",
        "GMT Standard Time" to "Europe/London",
        "W. Europe Standard Time" to "Europe/Berlin",
        "Romance Standard Time" to "Europe/Paris",
        "Central Europe Standard Time" to "Europe/Budapest",
        "UTC" to "UTC",
    )

    private fun alarm(p: Prop): Alarm? {
        if (p.params["VALUE"].equals("DATE-TIME", true)) {
            return time(p.value, emptyMap())?.zoned(ZoneOffset.UTC)?.toInstant()?.let { Alarm(null, false, it) }
        }
        val d = duration(p.value) ?: return null
        return Alarm(d, p.params["RELATED"].equals("END", true), null)
    }

    fun duration(v: String): Duration? = runCatching {
        val m = Regex("([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?").matchEntire(v.trim()) ?: return null
        val (sign, w, d, h, min, s) = m.destructured
        val total = Duration.ofDays((w.toLongOrNull() ?: 0) * 7 + (d.toLongOrNull() ?: 0))
            .plusHours(h.toLongOrNull() ?: 0).plusMinutes(min.toLongOrNull() ?: 0).plusSeconds(s.toLongOrNull() ?: 0)
        if (sign == "-") total.negated() else total
    }.getOrNull()

    private fun rrule(v: String): RRule? {
        val parts = v.split(';').mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) null else it.substring(0, eq).uppercase() to it.substring(eq + 1)
        }.toMap()
        fun ints(key: String) = parts[key]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        return RRule(
            freq = parts["FREQ"]?.uppercase() ?: return null,
            interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            count = parts["COUNT"]?.toIntOrNull(),
            until = parts["UNTIL"]?.let { time(it, emptyMap()) },
            byDay = parts["BYDAY"]?.split(',').orEmpty().mapNotNull { day ->
                val m = Regex("([+-]?\\d+)?(MO|TU|WE|TH|FR|SA|SU)").matchEntire(day.trim().uppercase()) ?: return@mapNotNull null
                m.groupValues[1].toIntOrNull() to DAYS.getValue(m.groupValues[2])
            },
            byMonthDay = ints("BYMONTHDAY"),
            byMonth = ints("BYMONTH"),
            bySetPos = ints("BYSETPOS"),
        )
    }

    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )
}

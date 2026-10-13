package com.jmsbrain.mailcal

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransitionRule
import java.util.UUID

/** One property line; [params] keep their raw text (quotes and all). */
class VProp(val name: String, val params: List<Pair<String, String>>, val value: String) {
    fun param(key: String) = params.firstOrNull { it.first.equals(key, true) }?.second?.trim('"')
    fun copy() = VProp(name, params.toList(), value)
}

class VComp(val name: String) {
    val props = mutableListOf<VProp>()
    val children = mutableListOf<VComp>()

    fun prop(name: String) = props.firstOrNull { it.name == name }
    fun value(name: String) = prop(name)?.value
    fun remove(vararg names: String) = props.removeAll { it.name in names }
    fun set(p: VProp) {
        val i = props.indexOfFirst { it.name == p.name }
        if (i >= 0) {
            props.removeAll { it.name == p.name }
            props.add(i.coerceAtMost(props.size), p)
        } else props += p
    }
    fun setText(name: String, text: String) {
        if (text.isBlank()) remove(name) else set(VProp(name, emptyList(), VCal.escape(text)))
    }
    fun deepCopy(): VComp = VComp(name).also { c ->
        props.forEach { c.props += it.copy() }
        children.forEach { c.children += it.deepCopy() }
    }
}

/** How an event's times are written: UTC, a named zone, or floating. */
sealed class TimeMode {
    object Utc : TimeMode()
    object Floating : TimeMode()
    data class Zone(val tzid: String, val zone: ZoneId) : TimeMode()
}

enum class Repeat(val label: String, val freq: String?) {
    None("NEVER", null), Daily("DAILY", "DAILY"), Weekly("WEEKLY", "WEEKLY"),
    Monthly("MONTHLY", "MONTHLY"), Yearly("YEARLY", "YEARLY"),
    /** A rule this app can't build; left as is. */
    Custom("CUSTOM (KEPT)", null),
}

/** How long before the start the reminder goes off. */
enum class Reminder(val label: String, val minutes: Long?) {
    None("NONE", null),
    AtStart("AT TIME OF EVENT", 0), Min30("30 MIN BEFORE", 30), Hour1("1 HOUR BEFORE", 60),
    Hour12("12 HOURS BEFORE", 12 * 60), Day1("1 DAY BEFORE", 24 * 60), Day3("3 DAYS BEFORE", 3 * 24 * 60),
    Week1("1 WEEK BEFORE", 7 * 24 * 60), Week2("2 WEEKS BEFORE", 14 * 24 * 60),
    /** Reminders this app can't build (several, or other times); left as is. */
    Custom("CUSTOM (KEPT)", null),
}

/** What the editor shows; times are in the phone's zone. End is inclusive for all-day. */
data class Draft(
    val title: String,
    val location: String,
    val notes: String,
    val allDay: Boolean,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val repeat: Repeat,
    val reminder: Reminder = Reminder.Custom,
)

/** Reads and edits raw iCalendar text so unknown properties (alarms, attendees…) survive. */
object VCal {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    // ---- text ----

    fun parse(text: String): VComp? {
        val stack = ArrayDeque<VComp>()
        var root: VComp? = null
        for (line in unfold(text)) {
            val p = parseLine(line) ?: continue
            when (p.name) {
                "BEGIN" -> {
                    val c = VComp(p.value.trim().uppercase())
                    stack.lastOrNull()?.children?.add(c)
                    if (stack.isEmpty() && root == null) root = c
                    stack.addLast(c)
                }
                "END" -> stack.removeLastOrNull()
                else -> stack.lastOrNull()?.props?.add(p)
            }
        }
        return root
    }

    fun write(root: VComp): String = buildString {
        fun emit(c: VComp) {
            line("BEGIN:${c.name}")
            c.props.forEach { p -> line(p.name + p.params.joinToString("") { ";${it.first}=${it.second}" } + ":" + p.value) }
            c.children.forEach(::emit)
            line("END:${c.name}")
        }
        emit(root)
    }

    // Folds at 75 bytes without splitting a character.
    private fun StringBuilder.line(l: String) {
        var bytes = 0
        var first = true
        for (ch in l) {
            val n = ch.toString().toByteArray().size
            if (bytes + n > (if (first) 75 else 74)) {
                append("\r\n ")
                bytes = 0
                first = false
            }
            append(ch)
            bytes += n
        }
        append("\r\n")
    }

    private fun unfold(text: String): List<String> {
        val lines = mutableListOf<String>()
        for (raw in text.split("\r\n", "\n", "\r")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) lines[lines.lastIndex] += raw.substring(1)
            else if (raw.isNotEmpty()) lines += raw
        }
        return lines
    }

    private fun parseLine(line: String): VProp? {
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) { colon = i; break }
            }
        }
        if (colon < 0) return null
        val head = splitParams(line.substring(0, colon))
        val params = head.drop(1).mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) null else it.substring(0, eq).uppercase() to it.substring(eq + 1)
        }
        return VProp(head[0].uppercase(), params, line.substring(colon + 1))
    }

    private fun splitParams(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        for (c in s) {
            when {
                c == '"' -> { quoted = !quoted; sb.append(c) }
                c == ';' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
        }
        out += sb.toString()
        return out
    }

    fun escape(t: String) = t.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace("\r\n", "\\n").replace("\n", "\\n")

    // ---- finding events ----

    private fun events(root: VComp) = root.children.filter { it.name == "VEVENT" }
    private fun master(root: VComp, uid: String) =
        events(root).firstOrNull { it.value("UID") == uid && it.prop("RECURRENCE-ID") == null }

    private fun instantOf(p: VProp, zone: ZoneId): Instant? =
        Ics.time(p.value, p.params.associate { it.first to it.second.trim('"') })?.zoned(zone)?.toInstant()

    private fun override(root: VComp, uid: String, original: ZonedDateTime, zone: ZoneId) =
        events(root).firstOrNull { e ->
            e.value("UID") == uid && e.prop("RECURRENCE-ID")?.let { instantOf(it, zone) } == original.toInstant()
        }

    fun modeOf(p: VProp?, phone: ZoneId): TimeMode {
        if (p == null || p.param("VALUE").equals("DATE", true) || p.value.length == 8) return TimeMode.Zone(phone.id, phone)
        if (p.value.endsWith("Z")) return TimeMode.Utc
        val tzid = p.param("TZID") ?: return TimeMode.Floating
        return Ics.zone(tzid)?.let { TimeMode.Zone(tzid, it) } ?: TimeMode.Utc
    }

    // ---- times ----

    private fun timeProp(name: String, t: LocalDateTime, allDay: Boolean, mode: TimeMode, phone: ZoneId): VProp = when {
        allDay -> VProp(name, listOf("VALUE" to "DATE"), t.toLocalDate().format(dateFmt))
        mode is TimeMode.Utc -> VProp(name, emptyList(), t.atZone(phone).withZoneSameInstant(ZoneOffset.UTC).format(dateTimeFmt) + "Z")
        mode is TimeMode.Floating -> VProp(name, emptyList(), t.format(dateTimeFmt))
        else -> {
            val z = mode as TimeMode.Zone
            VProp(name, listOf("TZID" to quoteIfNeeded(z.tzid)), t.atZone(phone).withZoneSameInstant(z.zone).format(dateTimeFmt))
        }
    }

    private fun quoteIfNeeded(v: String) = if (v.any { it in ":;," }) "\"$v\"" else v

    /** Same form as [like] (date, UTC, zone or floating), for EXDATE and RECURRENCE-ID. */
    private fun sameForm(name: String, like: VProp, at: ZonedDateTime): VProp {
        if (like.param("VALUE").equals("DATE", true) || like.value.length == 8) {
            return VProp(name, listOf("VALUE" to "DATE"), at.toLocalDate().format(dateFmt))
        }
        if (like.value.endsWith("Z")) return VProp(name, emptyList(), at.withZoneSameInstant(ZoneOffset.UTC).format(dateTimeFmt) + "Z")
        val tzid = like.param("TZID")
        val zone = tzid?.let(Ics::zone)
        return if (tzid != null && zone != null) {
            VProp(name, listOf("TZID" to quoteIfNeeded(tzid)), at.withZoneSameInstant(zone).format(dateTimeFmt))
        } else VProp(name, emptyList(), at.toLocalDateTime().format(dateTimeFmt))
    }

    private fun applyDraft(e: VComp, d: Draft, mode: TimeMode, phone: ZoneId) {
        e.setText("SUMMARY", d.title)
        e.setText("LOCATION", d.location)
        e.setText("DESCRIPTION", d.notes)
        e.remove("DURATION")
        e.set(timeProp("DTSTART", d.start, d.allDay, mode, phone))
        val end = if (d.allDay) d.end.toLocalDate().plusDays(1).atStartOfDay() else d.end
        e.set(timeProp("DTEND", end, d.allDay, mode, phone))
        setReminder(e, d.reminder, d.title)
        touch(e)
    }

    // Replaces the event's reminders only if the choice changed.
    private fun setReminder(e: VComp, r: Reminder, title: String) {
        if (r == Reminder.Custom || r == reminderOf(e)) return
        e.children.removeAll { it.name == "VALARM" }
        val minutes = r.minutes ?: return
        e.children += VComp("VALARM").apply {
            props += VProp("ACTION", emptyList(), "DISPLAY")
            props += VProp("DESCRIPTION", emptyList(), escape(title.ifBlank { "Reminder" }))
            props += VProp("TRIGGER", emptyList(), trigger(minutes))
        }
    }

    private fun trigger(minutes: Long): String = when {
        minutes == 0L -> "PT0S"
        minutes % (7 * 24 * 60) == 0L -> "-P${minutes / (7 * 24 * 60)}W"
        minutes % (24 * 60) == 0L -> "-P${minutes / (24 * 60)}D"
        minutes % 60 == 0L -> "-PT${minutes / 60}H"
        else -> "-PT${minutes}M"
    }

    /** The menu choice for an event's reminders. */
    private fun reminderOf(e: VComp): Reminder {
        val alarms = e.children.filter { it.name == "VALARM" }
        if (alarms.isEmpty()) return Reminder.None
        val t = alarms.singleOrNull()?.prop("TRIGGER") ?: return Reminder.Custom
        if (t.param("VALUE").equals("DATE-TIME", true) || t.param("RELATED").equals("END", true)) return Reminder.Custom
        val before = Ics.duration(t.value)?.negated() ?: return Reminder.Custom
        return Reminder.entries.firstOrNull { it.minutes != null && it.minutes == before.toMinutes() && before.seconds % 60 == 0L }
            ?: Reminder.Custom
    }

    /** The reminder choice for [occ]: its own override if it has one, else the series. */
    fun reminderOf(text: String, occ: Occurrence, one: Boolean, phone: ZoneId): Reminder {
        val root = parse(text) ?: return Reminder.Custom
        val own = override(root, occ.uid, occ.originalStart ?: occ.start, phone)
        val e = (if (one) own ?: master(root, occ.uid) else master(root, occ.uid) ?: own) ?: return Reminder.Custom
        return reminderOf(e)
    }

    private fun touch(e: VComp) {
        val now = ZonedDateTime.now(ZoneOffset.UTC).format(dateTimeFmt) + "Z"
        e.set(VProp("DTSTAMP", emptyList(), now))
        e.set(VProp("LAST-MODIFIED", emptyList(), now))
        e.set(VProp("SEQUENCE", emptyList(), ((e.value("SEQUENCE")?.toIntOrNull() ?: 0) + 1).toString()))
    }

    private fun ensureZone(root: VComp, mode: TimeMode) {
        if (mode !is TimeMode.Zone) return
        if (root.children.any { it.name == "VTIMEZONE" && it.value("TZID") == mode.tzid }) return
        root.children.add(0, vtimezone(mode.tzid, mode.zone))
    }

    // ---- edits ----

    /** A new calendar file and its UID. */
    fun create(d: Draft, phone: ZoneId): Pair<String, String> {
        val uid = UUID.randomUUID().toString()
        val root = VComp("VCALENDAR")
        root.props += VProp("VERSION", emptyList(), "2.0")
        root.props += VProp("PRODID", emptyList(), "-//jmsbrain//Mailcal//EN")
        val mode = TimeMode.Zone(phone.id, phone)
        if (!d.allDay) ensureZone(root, mode)
        val e = VComp("VEVENT")
        e.props += VProp("UID", emptyList(), uid)
        e.props += VProp("CREATED", emptyList(), ZonedDateTime.now(ZoneOffset.UTC).format(dateTimeFmt) + "Z")
        applyDraft(e, d, mode, phone)
        e.remove("SEQUENCE")
        setRepeat(root, e, d.repeat, uid)
        root.children += e
        return write(root) to uid
    }

    /**
     * Edits the whole event. For a series, the time change made to [occ] moves every repeat.
     */
    fun updateAll(text: String, occ: Occurrence, d: Draft, phone: ZoneId): String {
        val root = parse(text) ?: error("unreadable event")
        val e = master(root, occ.uid) ?: override(root, occ.uid, occ.originalStart ?: occ.start, phone) ?: error("event missing")
        val mode = modeOf(e.prop("DTSTART"), phone)
        if (occ.recurring && e.prop("RECURRENCE-ID") == null) {
            // Shift the series start by however much this occurrence moved.
            val oldStart = Ics.time(e.value("DTSTART")!!, e.prop("DTSTART")!!.params.associate { it.first to it.second.trim('"') })!!
                .zoned(phone).withZoneSameInstant(phone).toLocalDateTime()
            val shift = java.time.Duration.between(occ.start.withZoneSameInstant(phone).toLocalDateTime(), d.start)
            val length = java.time.Duration.between(d.start, d.end)
            val days = java.time.temporal.ChronoUnit.DAYS.between(occ.start.withZoneSameInstant(phone).toLocalDate(), d.start.toLocalDate())
            val newStart = if (d.allDay) oldStart.toLocalDate().plusDays(days).atStartOfDay() else oldStart.plus(shift)
            val series = d.copy(start = newStart, end = newStart.plus(length))
            if (!shift.isZero) {
                e.props.filter { it.name == "EXDATE" }.forEach { ex ->
                    val shifted = ex.value.split(',').mapNotNull { v ->
                        Ics.time(v, ex.params.associate { it.first to it.second.trim('"') })?.zoned(phone)?.plus(shift)
                    }
                    if (shifted.isNotEmpty()) {
                        val first = sameForm("EXDATE", ex, shifted.first())
                        val vals = shifted.map { sameForm("EXDATE", ex, it).value }
                        e.props[e.props.indexOf(ex)] = VProp("EXDATE", first.params, vals.joinToString(","))
                    }
                }
            }
            applyDraft(e, series, mode, phone)
        } else applyDraft(e, d, mode, phone)
        if (!d.allDay) ensureZone(root, mode)
        if (e.prop("RECURRENCE-ID") == null) setRepeat(root, e, d.repeat, occ.uid)
        return write(root)
    }

    /** Changes just this repeat, as an override of the series. */
    fun updateOne(text: String, occ: Occurrence, d: Draft, phone: ZoneId): String {
        val root = parse(text) ?: error("unreadable event")
        val original = occ.originalStart ?: occ.start
        val existing = override(root, occ.uid, original, phone)
        val m = master(root, occ.uid)
        val e = existing ?: run {
            m ?: error("event missing")
            val o = VComp("VEVENT")
            m.props.filter { it.name !in setOf("RRULE", "RDATE", "EXDATE", "DTSTART", "DTEND", "DURATION", "RECURRENCE-ID", "SEQUENCE") }
                .forEach { o.props += it.copy() }
            m.children.forEach { o.children += it.deepCopy() }
            o.props += sameForm("RECURRENCE-ID", m.prop("DTSTART")!!, original)
            root.children += o
            o
        }
        val mode = modeOf((m ?: e).prop("DTSTART"), phone)
        applyDraft(e, d, mode, phone)
        if (!d.allDay) ensureZone(root, mode)
        return write(root)
    }

    /** Skips one repeat of a series. */
    fun deleteOne(text: String, occ: Occurrence, phone: ZoneId): String {
        val root = parse(text) ?: error("unreadable event")
        val original = occ.originalStart ?: occ.start
        override(root, occ.uid, original, phone)?.let { root.children.remove(it) }
        val m = master(root, occ.uid) ?: error("event missing")
        m.props += sameForm("EXDATE", m.prop("DTSTART")!!, original)
        touch(m)
        return write(root)
    }

    private fun setRepeat(root: VComp, e: VComp, r: Repeat, uid: String) {
        when {
            r == Repeat.Custom -> Unit
            r.freq == null -> {
                if (e.prop("RRULE") != null || e.prop("RDATE") != null) {
                    e.remove("RRULE", "RDATE", "EXDATE")
                    root.children.removeAll { it.name == "VEVENT" && it.value("UID") == uid && it.prop("RECURRENCE-ID") != null }
                }
            }
            else -> {
                val old = e.prop("RRULE")?.value?.let { repeatOf(it) }
                if (old != r) e.set(VProp("RRULE", emptyList(), "FREQ=${r.freq}"))
            }
        }
    }

    /** The menu choice for an RRULE. */
    fun repeatOf(rrule: String?): Repeat {
        if (rrule == null) return Repeat.None
        val parts = rrule.split(';').associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
        if (parts.keys.any { it !in setOf("FREQ", "WKST") }) return Repeat.Custom
        return Repeat.entries.firstOrNull { it.freq != null && it.freq == parts["FREQ"]?.uppercase() } ?: Repeat.Custom
    }

    fun rruleOf(text: String, uid: String): String? =
        parse(text)?.let { master(it, uid)?.value("RRULE") }

    // ---- VTIMEZONE from the phone's zone rules ----

    fun vtimezone(tzid: String, zone: ZoneId): VComp {
        val tz = VComp("VTIMEZONE")
        tz.props += VProp("TZID", emptyList(), tzid)
        val rules = zone.rules
        val transitions = rules.transitionRules
        if (transitions.isEmpty()) {
            val off = rules.getOffset(Instant.now())
            tz.children += VComp("STANDARD").apply {
                props += VProp("DTSTART", emptyList(), "19700101T000000")
                props += VProp("TZOFFSETFROM", emptyList(), offset(off))
                props += VProp("TZOFFSETTO", emptyList(), offset(off))
            }
            return tz
        }
        for (t in transitions) {
            val dst = t.offsetAfter.totalSeconds > t.standardOffset.totalSeconds
            tz.children += VComp(if (dst) "DAYLIGHT" else "STANDARD").apply {
                props += VProp("DTSTART", emptyList(), t.createTransition(1970).dateTimeBefore.format(dateTimeFmt))
                props += VProp("TZOFFSETFROM", emptyList(), offset(t.offsetBefore))
                props += VProp("TZOFFSETTO", emptyList(), offset(t.offsetAfter))
                ruleOf(t)?.let { props += VProp("RRULE", emptyList(), it) }
            }
        }
        return tz
    }

    private fun offset(o: ZoneOffset): String {
        val s = o.totalSeconds
        val sign = if (s < 0) "-" else "+"
        val a = kotlin.math.abs(s)
        return "%s%02d%02d".format(sign, a / 3600, (a % 3600) / 60)
    }

    private fun ruleOf(t: ZoneOffsetTransitionRule): String? {
        val dow = t.dayOfWeek ?: return "FREQ=YEARLY;BYMONTH=${t.month.value};BYMONTHDAY=${t.dayOfMonthIndicator}"
        val ind = t.dayOfMonthIndicator
        val nth = when {
            ind < 0 -> -1
            (ind - 1) % 7 == 0 -> (ind - 1) / 7 + 1
            else -> return null
        }
        return "FREQ=YEARLY;BYMONTH=${t.month.value};BYDAY=$nth${DAYS.getValue(dow)}"
    }

    private val DAYS = mapOf(
        DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
        DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
    )
}

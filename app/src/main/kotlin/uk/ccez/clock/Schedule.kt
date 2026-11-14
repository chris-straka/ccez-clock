package uk.ccez.clock

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * When alarms ring. Pure: no Android, so the unit tests cover it whole.
 *
 * Daylight saving: a time the clocks skip (02:30 on a spring-forward
 * night) rings at the same wall time pushed past the gap (03:30); a time
 * that happens twice in autumn rings once, at the first (java.time's
 * ZonedDateTime.of rules, written down here on purpose).
 */
object Schedule {
    /** The next time [alarm] rings strictly after [now], or null when it's off. */
    fun nextRing(alarm: Alarm, now: ZonedDateTime): ZonedDateTime? {
        if (!alarm.enabled || alarm.deleted) return null
        val time = LocalTime.of(alarm.hour, alarm.minute)
        val today = now.toLocalDate()
        for (d in 0L..7L) {
            val date = today.plusDays(d)
            if (alarm.repeats && date.dayOfWeek !in alarm.days) continue
            val at = ZonedDateTime.of(LocalDateTime.of(date, time), now.zone)
            if (at.isAfter(now)) return at
        }
        return null
    }

    /** The next ring, counting a snooze that's sooner. */
    fun nextRing(alarm: Alarm, snoozedUntil: Long?, now: ZonedDateTime): ZonedDateTime? {
        val planned = nextRing(alarm, now)
        if (alarm.deleted || snoozedUntil == null || snoozedUntil <= now.toInstant().toEpochMilli()) return planned
        val snooze = ZonedDateTime.ofInstant(Instant.ofEpochMilli(snoozedUntil), now.zone)
        return if (planned == null || snooze.isBefore(planned)) snooze else planned
    }

    fun snoozeUntil(nowMs: Long, minutes: Int): Long = nowMs + minutes * 60_000L

    /**
     * Where a ring that started at [startedMs] rings again if it never ends
     * (the process killed): when it would have gone quiet, plus a snooze.
     */
    fun retryAt(startedMs: Long, ringMinutes: Int, snoozeMinutes: Int): Long =
        startedMs + ringMinutes * 60_000L + snoozeMinutes * 60_000L

    /** How long a timer that's over can still ring (synced late, a reboot); older is missed. */
    const val TIMER_LATE_MS = 10 * 60_000L

    /** When to wake for a timer ending at [endsAt]: then, now when it's just over, or null when it's long missed. */
    fun timerWake(endsAt: Long, nowMs: Long): Long? = when {
        endsAt > nowMs -> endsAt
        nowMs - endsAt < TIMER_LATE_MS -> nowMs
        else -> null
    }

    /** "In 7 h 30 min", "In 45 min", "In 2 days". Rounded up to the minute, as people read alarms. */
    fun untilText(now: ZonedDateTime, ring: ZonedDateTime): String {
        val secs = Duration.between(now, ring).seconds.coerceAtLeast(0)
        val mins = (secs + 59) / 60
        val days = mins / (24 * 60)
        val h = (mins % (24 * 60)) / 60
        val m = mins % 60
        return when {
            days >= 1 && h == 0L -> "In $days ${if (days == 1L) "day" else "days"}"
            days >= 1 -> "In $days d $h h"
            h >= 1 && m == 0L -> "In $h h"
            h >= 1 -> "In $h h $m min"
            m <= 1 -> "In 1 min"
            else -> "In $m min"
        }
    }

    /** "Once", "Every day", "Weekdays", "Weekends", "Mon, Wed, Fri" (in the locale's week order). */
    fun daysText(days: Set<DayOfWeek>, locale: Locale = Locale.getDefault()): String = when {
        days.isEmpty() -> "Once"
        days.size == 7 -> "Every day"
        days == Days.weekdays -> "Weekdays"
        days == Days.weekend -> "Weekends"
        else -> weekOrder(locale).filter { it in days }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
    }

    /** Spoken form for TalkBack: full day names. */
    fun daysSpoken(days: Set<DayOfWeek>, locale: Locale = Locale.getDefault()): String = when {
        days.isEmpty() || days.size == 7 || days == Days.weekdays || days == Days.weekend -> daysText(days, locale)
        else -> weekOrder(locale).filter { it in days }.joinToString(", ") { it.getDisplayName(TextStyle.FULL, locale) }
    }

    fun weekOrder(locale: Locale): List<DayOfWeek> {
        val first = WeekFields.of(locale).firstDayOfWeek
        return (0L until 7L).map { first.plus(it) }
    }

    /** "7:30" + "AM" in 12-hour; "07:30" + "" in 24-hour. */
    fun timeParts(hour: Int, minute: Int, h24: Boolean): Pair<String, String> =
        if (h24) "%02d:%02d".format(hour, minute) to ""
        else {
            val h = if (hour % 12 == 0) 12 else hour % 12
            "%d:%02d".format(h, minute) to if (hour < 12) "AM" else "PM"
        }

    fun timeText(hour: Int, minute: Int, h24: Boolean): String {
        val (t, ap) = timeParts(hour, minute, h24)
        return if (ap.isEmpty()) t else "$t $ap"
    }

    /** "+5" (minutes from now, rounded up to the next whole minute) or "HH:MM". */
    fun resolveTime(s: String, now: ZonedDateTime): Pair<Int, Int> {
        val rel = Regex("""^\s*\+(\d{1,4})\s*$""").matchEntire(s)
        if (rel != null) {
            val mins = rel.groupValues[1].toLong()
            var t = now.plusMinutes(mins)
            if (t.second != 0 || t.nano != 0) t = t.withSecond(0).withNano(0).plusMinutes(1)
            return t.hour to t.minute
        }
        return Alarm.parseTime(s)
    }
}

/** Durations as the timer and stopwatch show them. */
object Durations {
    /** "4:05", "1:02:03" (timer: rounded up to the second, so it never shows 0:00 early). */
    fun timer(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "00:12.34", "1:02:03.45" (stopwatch: hundredths, rounded down). */
    fun stopwatch(ms: Long): String {
        val cs = ms.coerceAtLeast(0) / 10
        val h = cs / 360_000
        val m = (cs % 360_000) / 6000
        val s = (cs % 6000) / 100
        val c = cs % 100
        return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, s, c) else "%02d:%02d.%02d".format(m, s, c)
    }

    /** "5 minutes", "1 hour 30 minutes", "45 seconds", for TalkBack. */
    fun spoken(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val parts = buildList {
            if (h > 0) add("$h ${if (h == 1L) "hour" else "hours"}")
            if (m > 0) add("$m ${if (m == 1L) "minute" else "minutes"}")
            if (sec > 0 || isEmpty()) add("$sec ${if (sec == 1L) "second" else "seconds"}")
        }
        return parts.joinToString(" ")
    }

    /** "90", "5m", "1h30m", "2m30s" → milliseconds. */
    fun parse(s: String): Long {
        val t = s.trim().lowercase()
        t.toLongOrNull()?.let { require(it > 0) { "a duration is more than 0" }; return it * 1000 }
        val m = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""").matchEntire(t)
        require(m != null && t.isNotEmpty()) { "durations look like 90, 5m, 1h30m, 2m30s" }
        val (h, mi, se) = m.destructured
        val ms = ((h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (se.toLongOrNull() ?: 0)) * 1000
        require(ms > 0) { "a duration is more than 0" }
        require(ms < 100L * 3600_000) { "the timer goes up to 99 hours" }
        return ms
    }

    /** The timer keypad: digits typed in from the right, "130" is 1:30, "13000" is 1:30:00. */
    fun fromDigits(digits: String): Long {
        val d = digits.takeLast(6).padStart(6, '0')
        val h = d.substring(0, 2).toLong()
        val m = d.substring(2, 4).toLong()
        val s = d.substring(4, 6).toLong()
        return ((h * 3600) + m * 60 + s) * 1000
    }
}

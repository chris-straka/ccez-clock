package uk.ccez.clock

import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleTest {
    private val toronto = ZoneId.of("America/Toronto")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0, zone: ZoneId = toronto) = ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone)
    private fun alarm(h: Int, m: Int, vararg days: java.time.DayOfWeek, enabled: Boolean = true) =
        Alarm("a", h, m, days.toSet(), enabled)

    @Test fun onceLaterTodayOrTomorrow() {
        // 2026-11-12 is a Thursday.
        val now = at(2026, 11, 12, 6, 0)
        assertEquals(at(2026, 11, 12, 7, 30), Schedule.nextRing(alarm(7, 30), now))
        assertEquals(at(2026, 11, 13, 5, 0), Schedule.nextRing(alarm(5, 0), now))
    }

    @Test fun theMinuteItRingsCountsAsPassed() {
        // Exactly at 07:30:00 (the fire itself): the next ring is tomorrow, never again now.
        assertEquals(at(2026, 11, 13, 7, 30), Schedule.nextRing(alarm(7, 30), at(2026, 11, 12, 7, 30)))
        // A second before: still today.
        assertEquals(at(2026, 11, 12, 7, 30), Schedule.nextRing(alarm(7, 30), at(2026, 11, 12, 7, 29, 59)))
    }

    @Test fun offOrDeletedNeverRings() {
        assertNull(Schedule.nextRing(alarm(7, 0, enabled = false), at(2026, 11, 12, 6, 0)))
        assertNull(Schedule.nextRing(alarm(7, 0).copy(deleted = true), at(2026, 11, 12, 6, 0)))
    }

    @Test fun repeatDaysSkipToTheNextChosenDay() {
        // Thursday evening, a Mon/Wed/Fri alarm: Friday.
        val thu = at(2026, 11, 12, 20, 0)
        assertEquals(at(2026, 11, 13, 7, 0), Schedule.nextRing(alarm(7, 0, MONDAY, WEDNESDAY, FRIDAY), thu))
        // Friday after it rang: Monday.
        assertEquals(at(2026, 11, 16, 7, 0), Schedule.nextRing(alarm(7, 0, MONDAY, WEDNESDAY, FRIDAY), at(2026, 11, 13, 7, 0)))
        // Weekends from a Thursday: Saturday.
        assertEquals(at(2026, 11, 14, 9, 0), Schedule.nextRing(alarm(9, 0, SATURDAY, SUNDAY), thu))
        // One day a week, already past today: a week on.
        assertEquals(at(2026, 11, 19, 7, 0), Schedule.nextRing(alarm(7, 0, java.time.DayOfWeek.THURSDAY), at(2026, 11, 12, 8, 0)))
        // Same day, still to come.
        assertEquals(at(2026, 11, 12, 9, 0), Schedule.nextRing(alarm(9, 0, java.time.DayOfWeek.THURSDAY), at(2026, 11, 12, 8, 0)))
    }

    @Test fun springForwardGapRingsPastTheGap() {
        // 2026-03-08 in Toronto: 02:00 → 03:00. A 02:30 alarm rings at 03:30 EDT that day.
        val r = Schedule.nextRing(alarm(2, 30), at(2026, 3, 8, 1, 0))!!
        assertEquals(3, r.hour)
        assertEquals(30, r.minute)
        assertEquals(8, r.dayOfMonth)
        // An alarm set for 07:00 that morning is 07:00 EDT: 5 hours after 01:00 EST (the hour lost).
        val seven = Schedule.nextRing(alarm(7, 0), at(2026, 3, 8, 1, 0))!!
        assertEquals(7, seven.hour)
        assertEquals(5 * 3600L, java.time.Duration.between(at(2026, 3, 8, 1, 0), seven).seconds)
    }

    @Test fun fallBackOverlapRingsOnceAtTheFirst() {
        // 2026-11-01 in Toronto: 02:00 EDT → 01:00 EST. 01:30 happens twice; it rings at the first.
        val before = at(2026, 11, 1, 0, 30)
        val r = Schedule.nextRing(alarm(1, 30), before)!!
        assertEquals(ZoneId.of("America/Toronto").rules.getOffset(r.toInstant()).totalSeconds, -4 * 3600)
        // Asked again just after that first 01:30, it's tomorrow, not the second 01:30.
        val again = Schedule.nextRing(alarm(1, 30), r)!!
        assertEquals(2, again.dayOfMonth)
    }

    @Test fun anotherTimeZoneRingsAtLocalTime() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val r = Schedule.nextRing(alarm(7, 0), at(2026, 11, 12, 6, 0, zone = tokyo))!!
        assertEquals(tokyo, r.zone)
        assertEquals(7, r.hour)
    }

    @Test fun snoozeIsSoonerThanTheNextDayAndExpires() {
        val now = at(2026, 11, 12, 7, 0)
        val nowMs = now.toInstant().toEpochMilli()
        val until = Schedule.snoozeUntil(nowMs, 10)
        assertEquals(nowMs + 600_000, until)
        val a = alarm(7, 0)
        assertEquals(now.plusMinutes(10), Schedule.nextRing(a, until, now))
        // A snooze in the past is ignored.
        assertEquals(at(2026, 11, 13, 7, 0), Schedule.nextRing(a, nowMs - 1, now))
        // A turned-off alarm still keeps no snooze (the store drops it; nextRing with null).
        assertNull(Schedule.nextRing(alarm(7, 0, enabled = false), null, now))
    }

    @Test fun untilText() {
        val now = at(2026, 11, 12, 6, 0)
        assertEquals("In 1 h 30 min", Schedule.untilText(now, at(2026, 11, 12, 7, 30)))
        assertEquals("In 2 h", Schedule.untilText(now, at(2026, 11, 12, 8, 0)))
        assertEquals("In 45 min", Schedule.untilText(now, at(2026, 11, 12, 6, 45)))
        assertEquals("In 1 min", Schedule.untilText(now, at(2026, 11, 12, 6, 0, 20)))
        assertEquals("In 1 day", Schedule.untilText(now, at(2026, 11, 13, 6, 0)))
        assertEquals("In 1 d 2 h", Schedule.untilText(now, at(2026, 11, 13, 8, 0)))
    }

    @Test fun daysText() {
        val l = Locale.CANADA
        assertEquals("Once", Schedule.daysText(emptySet(), l))
        assertEquals("Weekdays", Schedule.daysText(Days.weekdays, l))
        assertEquals("Weekends", Schedule.daysText(Days.weekend, l))
        assertEquals("Every day", Schedule.daysText(java.time.DayOfWeek.entries.toSet(), l))
        // Canada's week starts on Sunday.
        assertEquals("Sun, Mon, Fri", Schedule.daysText(setOf(FRIDAY, MONDAY, SUNDAY), l))
        assertEquals("Sunday, Monday, Friday", Schedule.daysSpoken(setOf(FRIDAY, MONDAY, SUNDAY), l))
        assertEquals(MONDAY, Schedule.weekOrder(Locale.FRANCE).first())
    }

    @Test fun timeText() {
        assertEquals("7:05 AM", Schedule.timeText(7, 5, false))
        assertEquals("12:00 PM", Schedule.timeText(12, 0, false))
        assertEquals("12:30 AM", Schedule.timeText(0, 30, false))
        assertEquals("19:05", Schedule.timeText(19, 5, true))
    }

    @Test fun relativeTimesRoundUpToTheNextMinute() {
        assertEquals(6 to 5, Schedule.resolveTime("+5", at(2026, 11, 12, 6, 0)))
        assertEquals(6 to 6, Schedule.resolveTime("+5", at(2026, 11, 12, 6, 0, 10)))
        assertEquals(0 to 1, Schedule.resolveTime("+2", at(2026, 11, 12, 23, 59)))
        assertEquals(7 to 30, Schedule.resolveTime("7:30", at(2026, 11, 12, 6, 0)))
    }

    @Test fun aRingThatNeverEndsRingsAgainASnoozeAfterItWouldHaveGoneQuiet() {
        assertEquals(1_000L + 10 * 60_000 + 5 * 60_000, Schedule.retryAt(1_000, 10, 5))
    }

    @Test fun anUnansweredAlarmRingsAgainFiveMinutesLater() {
        // Owner, 2026-11-14: 5 minutes apart, as Samsung Clock does.
        assertEquals(5, SettingDefs.ALL.first { it.key == "alarm.ring_again_minutes" }.default)
        assertEquals(1_000L + 5 * 60_000, Schedule.snoozeUntil(1_000, 5))
    }

    @Test fun aTimerWakesAtItsEndOrNowWhenJustOver() {
        assertEquals(5_000L, Schedule.timerWake(5_000, 1_000))
        // Over by a few minutes (a late sync, a reboot): now.
        assertEquals(200_000L, Schedule.timerWake(5_000, 200_000))
        // Over by ten minutes or more: missed.
        assertNull(Schedule.timerWake(5_000, 5_000 + Schedule.TIMER_LATE_MS))
    }
}

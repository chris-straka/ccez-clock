package uk.ccez.clock

import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The alarm store's rules for snoozes, unanswered rings and the server's copies. */
class StoreTest {
    private val dir: File = Files.createTempDirectory("ccez-clock-store").toFile()
    private val log = ClockLog(dir)
    private fun store() = AlarmStore(dir, log)

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test fun anUnansweredAlarmRingsAgainThenCountsAsDismissed() {
        val s = store()
        val a = s.put(Alarm("reveil", 6, 30, label = "Réveil"), "add")
        s.ringing(a.id, 5_000)
        assertEquals(5_000L, s.snoozedUntil(a.id))
        // Three times a snooze apart, as Samsung Clock does by default.
        assertEquals(10_000L, s.unanswered(a.id, 10_000, 3))
        assertEquals(20_000L, s.unanswered(a.id, 20_000, 3))
        assertEquals(30_000L, s.unanswered(a.id, 30_000, 3))
        assertEquals(3, s.missedCount(a.id))
        assertTrue(s.get(a.id)!!.enabled)
        // The fourth time nobody answers, it's done: a one-off turns off.
        assertNull(s.unanswered(a.id, 40_000, 3))
        assertFalse(s.get(a.id)!!.enabled)
        assertNull(s.snoozedUntil(a.id))
        assertEquals(0, s.missedCount(a.id))
    }

    @Test fun ringAgainOffMeansDoneAtOnce() {
        val s = store()
        val a = s.put(Alarm("matin", 7, 0, setOf(DayOfWeek.MONDAY), label = "Morgen"), "add")
        assertNull(s.unanswered(a.id, 10_000, 0))
        // A repeating alarm stays on for next time.
        assertTrue(s.get(a.id)!!.enabled)
    }

    @Test fun aSnoozeByThePersonStartsTheCountOver() {
        val s = store()
        val a = s.put(Alarm("asa", 6, 0, label = "朝"), "add")
        s.unanswered(a.id, 10_000, 3)
        s.unanswered(a.id, 20_000, 3)
        s.snooze(a.id, 30_000)
        assertEquals(0, s.missedCount(a.id))
        assertEquals(30_000L, s.unanswered(a.id, 30_000, 3))
    }

    @Test fun aRingKilledMidwayStillRingsAgainAfterARestart() {
        val s = store()
        val a = s.put(Alarm("ahchim", 6, 0, label = "아침"), "add")
        s.ringing(a.id, 1_234_000)
        s.unanswered(a.id, 2_000_000, 3)
        // A new process reads the file: the retry and the count are still there.
        val again = store()
        assertEquals(2_000_000L, again.snoozedUntil(a.id))
        assertEquals(1, again.missedCount(a.id))
        assertTrue(again.get(a.id)!!.enabled)
    }

    @Test fun movingASnoozedAlarmEndsTheSnooze() {
        val s = store()
        val a = s.put(Alarm("sport", 7, 0, label = "Sport"), "add")
        s.snooze(a.id, 99_000)
        // A new label keeps the snooze.
        s.put(s.get(a.id)!!.copy(label = "Sport et yoga"))
        assertEquals(99_000L, s.snoozedUntil(a.id))
        // A new time ends it.
        s.put(s.get(a.id)!!.copy(hour = 8))
        assertNull(s.snoozedUntil(a.id))
        // So do new days.
        s.snooze(a.id, 99_000)
        s.put(s.get(a.id)!!.copy(days = setOf(DayOfWeek.SATURDAY)))
        assertNull(s.snoozedUntil(a.id))
        // And an agent moving it through the server.
        s.snooze(a.id, 99_000)
        val moved = s.get(a.id)!!.copy(minute = 45, at = s.get(a.id)!!.at + 10)
        s.applyRemote(listOf(moved))
        assertNull(s.snoozedUntil(a.id))
    }

    @Test fun theServersCopyWinsWhenThePhoneOwesNothing() {
        val s = store()
        // The phone's clock runs a minute ahead of the server's.
        val mine = s.put(Alarm("gym", 7, 0, label = "Fitnessstudio"), "add")
        s.pushed(listOf(mine))
        // An agent moved it on the server, stamped by the server's clock: earlier than the phone's stamp.
        val agents = mine.copy(hour = 9, at = mine.at - 60_000)
        assertEquals(1, s.applyRemote(listOf(agents)))
        assertEquals(9, s.get(mine.id)!!.hour)
        // The same copy again changes nothing.
        assertEquals(0, s.applyRemote(listOf(agents)))
    }

    @Test fun anUnsentChangeOnThePhoneIsKeptAgainstAnOlderServerCopy() {
        val s = store()
        val mine = s.put(Alarm("yoga", 7, 0, label = "Yoga"), "add")
        val older = mine.copy(hour = 5, at = mine.at - 1)
        assertEquals(0, s.applyRemote(listOf(older)))
        assertEquals(7, s.get(mine.id)!!.hour)
        assertEquals(listOf(mine.id), s.dirtyAlarms().map { it.id })
    }

    @Test fun aNewServerDatabaseGetsEveryAlarmAgain() {
        val s = store()
        val a = s.put(Alarm("un", 6, 0, label = "Un"), "add")
        val b = s.put(Alarm("deux", 7, 0, label = "Deux"), "add")
        s.pushed(listOf(a, b))
        assertTrue(s.dirtyAlarms().isEmpty())
        s.markAllDirty()
        assertEquals(setOf("un", "deux"), s.dirtyAlarms().map { it.id }.toSet())
        assertTrue(SyncCursor.newDatabase(1_700_000_000_000, 1_800_000_000_000))
        assertFalse(SyncCursor.newDatabase(0, 1_800_000_000_000))
        assertFalse(SyncCursor.newDatabase(1_700_000_000_000, 1_700_000_000_000))
        assertFalse(SyncCursor.newDatabase(1_700_000_000_000, 0))
    }

    @Test fun selfUpdateIsOnUnlessChosenOff() {
        assertEquals(true, ClockSettings(dir, log).bool("updates.self"))
        // An old file held every default, the old updates.self=false among them: not a choice.
        File(dir, "settings.json").writeText(JSONObject().put("updates.self", false).put("alarm.sound", "dawn").toString())
        val migrated = ClockSettings(dir, log)
        assertTrue(migrated.bool("updates.self"))
        assertEquals("dawn", migrated.text("alarm.sound"))
        // Only what was set is saved, so a later default change reaches the phone.
        val saved = JSONObject(File(dir, "settings.json").readText())
        assertFalse(saved.has("updates.self"))
        assertFalse(saved.has("timer.sound"))
        // Turned off on purpose, it stays off.
        migrated.set("updates.self", "false")
        assertFalse(ClockSettings(dir, log).bool("updates.self"))
    }

    @Test fun twoAlarmsInTheSameMinuteRingAsOne() {
        val gym = Ring("alarm", listOf("gym"), "Sport", 1_000, 1_000, 10)
        val pills = Ring("alarm", listOf("pills"), "Médicaments", 1_000, 1_500, 5)
        val both = gym.join(pills)
        assertEquals(listOf("gym", "pills"), both.alarmIds)
        assertEquals("Sport · Médicaments", both.label)
        assertEquals("gym", both.alarmId)
        // The same alarm twice, or one without a label, adds nothing.
        assertEquals(both, both.join(Ring("alarm", listOf("pills"), "Médicaments", 1_000, 2_000, 5)))
        assertEquals("Sport", gym.join(Ring("alarm", listOf("x"), "", 1_000, 1_000, 10)).label)
    }

    @Test fun theAlarmTabWarnsOfWhatKeepsAlarmsFromRinging() {
        val checks = listOf(
            Health.Check("exact_alarms", "Exact alarms", true),
            Health.Check("full_screen", "Full-screen alarms", false),
            Health.Check("alarm_volume", "Alarm volume on", false),
        )
        assertEquals(listOf("full_screen"), Health.warnings(checks, 40).map { it.key })
        // With nothing raising a muted alarm volume, that's a warning too.
        assertEquals(listOf("full_screen", "alarm_volume"), Health.warnings(checks, 0).map { it.key })
    }
}

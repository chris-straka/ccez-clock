package uk.ccez.clock

import java.time.DayOfWeek
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelTest {
    @Test fun alarmJsonRoundTrip() {
        val a = Alarm("x1", 6, 45, setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY), true, "Gym", "dawn", false, 15, 123, false)
        val o = a.toJson()
        assertEquals("06:45", o.getString("time"))
        assertEquals("[\"mon\",\"fri\"]", o.getJSONArray("days").toString())
        assertEquals(a, Alarm.parse(JSONObject(o.toString())))
    }

    @Test fun alarmJsonFromAgentsIsChecked() {
        val ok = Alarm.parse(JSONObject("""{"id":"a","time":"7:05","days":["Sat","sun"]}"""))
        assertEquals(7, ok.hour)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), ok.days)
        assertTrue(ok.vibrate)
        assertEquals(10, ok.snooze)
        for (bad in listOf(
            """{"id":"a","time":"24:00"}""",
            """{"id":"a","time":"7"}""",
            """{"id":"a b","time":"07:00"}""",
            """{"id":"a","time":"07:00","days":["funday"]}""",
            """{"id":"a","time":"07:00","sound":"airhorn"}""",
            """{"id":"a","time":"07:00","snooze":0}""",
        )) {
            try {
                Alarm.parse(JSONObject(bad))
                fail("took $bad")
            } catch (_: Exception) {
            }
        }
    }

    @Test fun timerLifecycle() {
        val t0 = TimerState().start(300_000, 1_000)
        assertTrue(t0.running)
        assertEquals(300_000, t0.left(1_000))
        assertEquals(200_000, t0.left(101_000))
        val p = t0.pause(101_000)
        assertTrue(p.paused)
        assertEquals(200_000, p.left(999_999))
        val r = p.resume(500_000)
        assertEquals(700_000, r.endsAt)
        val plus = r.addMinute(600_000)
        assertEquals(760_000, plus.endsAt)
        assertEquals(360_000, plus.total)
        assertTrue(plus.cancel(600_000).idle)
        // +1 min on an idle timer starts a minute.
        assertEquals(60_000, TimerState().addMinute(0).left(0))
        // Never below zero.
        assertEquals(0, t0.left(10_000_000))
        assertEquals(t0, TimerState.parse(JSONObject(t0.toJson().toString())))
    }

    @Test fun stopwatchByTimestamps() {
        var s = StopwatchState().start(1_000, 50_000, 3)
        assertEquals(500, s.elapsed(1_500, 50_500, 3))
        s = s.lap(2_000, 51_000, 3).lap(2_500, 51_500, 3)
        assertEquals(listOf(Triple(2, 500L, 1500L), Triple(1, 1000L, 1000L)), s.lapRows())
        s = s.stop(3_000, 52_000, 3)
        assertFalse(s.running)
        assertEquals(2_000, s.elapsed(99_999, 99_999, 3))
        s = s.start(10_000, 60_000, 3)
        assertEquals(3_000, s.elapsed(11_000, 61_000, 3))
        // After a reboot (another boot count, elapsed clock back near zero) the wall clock carries it.
        assertEquals(2_000 + 5_000, s.elapsed(200, 65_000, 4))
        assertEquals(s, StopwatchState.parse(JSONObject(s.toJson().toString())))
        assertEquals(StopwatchState(), s.reset())
    }

    @Test fun durations() {
        assertEquals("5:00", Durations.timer(300_000))
        assertEquals("0:01", Durations.timer(1))
        assertEquals("1:00:00", Durations.timer(3_600_000))
        assertEquals("00:01.23", Durations.stopwatch(1_234))
        assertEquals("1:01:01.00", Durations.stopwatch(3_661_000))
        assertEquals("5 minutes", Durations.spoken(300_000))
        assertEquals("1 hour 30 minutes", Durations.spoken(5_400_000))
        assertEquals("1 second", Durations.spoken(400))
        assertEquals(90_000, Durations.parse("90"))
        assertEquals(5_400_000, Durations.parse("1h30m"))
        assertEquals(150_000, Durations.parse("2m30s"))
        assertEquals(90_000, Durations.fromDigits("130"))
        assertEquals(5_400_000, Durations.fromDigits("13000"))
        assertEquals(0, Durations.fromDigits(""))
    }

    @Test fun settingsTable() {
        val d = SettingDefs.def("alarm.fade_seconds")!!
        assertEquals(30, SettingDefs.checked(d, 30))
        assertThrows { SettingDefs.checked(d, 500) }
        assertThrows { SettingDefs.checked(SettingDefs.def("alarm.sound")!!, "airhorn") }
        assertEquals(listOf(1, 3, 5), SettingDefs.presets("1, 3,5"))
        assertThrows { SettingDefs.presets("1,x") }
        for (def in SettingDefs.ALL) SettingDefs.checked(def, def.default)
    }

    @Test fun everySoundRendersAtTheRightLevelAndLength() {
        for (id in Synth.SOUNDS) {
            val pcm = Synth.render(id)
            val secs = pcm.size.toDouble() / Synth.RATE
            assertTrue("$id is ${secs}s", secs in 1.0..6.0)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$id peak $peak", peak in 25_000..26_500)
            // Loudness: no silent sound, none a wall of noise.
            val rms = kotlin.math.sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
            assertTrue("$id rms $rms", rms in 1_000.0..16_000.0)
            // The loop seam: quiet at both ends, so it never clicks.
            assertTrue("$id starts loud", abs(pcm.first().toInt()) < 500)
            assertTrue("$id ends loud", abs(pcm.last().toInt()) < 2_000)
            // Same every time.
            assertArrayEquals(pcm, Synth.render(id))
        }
        val wav = Synth.wav(ShortArray(10))
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(64, wav.size)
    }

    private fun assertThrows(f: () -> Unit) {
        try {
            f()
            fail("didn't throw")
        } catch (_: Exception) {
        }
    }
}

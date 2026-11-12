package uk.ccez.clock

import android.content.Context
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole path, with nothing faked: an alarm set a minute ahead goes
 * through AlarmManager.setAlarmClock, the screen is off, and the ring
 * screen must come up over it (full-screen intent) with the screen on.
 */
@RunWith(AndroidJUnit4::class)
class AlarmFiresTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    companion object {
        @JvmStatic @BeforeClass fun quiet() = Fixtures.quiet()
    }

    @Before fun clean() {
        Fixtures.clear()
        if (RingService.ringing.value != null) RingService.send(ctx, RingService.DISMISS)
        device.wakeUp()
    }

    private fun shell(cmd: String) = device.executeShellCommand(cmd)

    private val power get() = ctx.getSystemService(PowerManager::class.java)

    /** Screen off, as on a night table; waits until the system says so. */
    private fun screenOff() {
        shell("input keyevent KEYCODE_SLEEP")
        val until = System.currentTimeMillis() + 10_000
        while (power.isInteractive && System.currentTimeMillis() < until) Thread.sleep(200)
        assertFalse("the screen didn't go off", power.isInteractive)
        Thread.sleep(1000)
    }

    @Test fun anAlarmAMinuteAheadRingsOverTheOffScreen() {
        val app = App.instance
        // The next whole minute at least 25 s away, so scheduling settles first.
        val now = ZonedDateTime.now()
        val (h, m) = Schedule.resolveTime(if (now.second < 35) "+1" else "+2", now)
        val a = app.alarms.put(Alarm(Alarm.newId(), h, m, label = "Fires"))
        // The system knows it as the next alarm clock (status bar, lock screen).
        assertEquals(Schedule.nextRing(a, now)!!.toInstant().toEpochMilli(), Scheduler.nextAlarmClock(ctx))

        screenOff()

        val dismiss = device.wait(Until.findObject(By.res("dismiss")), 150_000)
        assertNotNull("the ring screen never came up", dismiss)
        assertTrue("screen still off", ctx.getSystemService(PowerManager::class.java).isInteractive)
        assertTrue(shell("dumpsys activity activities").lines().any { it.contains("topResumedActivity") && it.contains("RingActivity") })
        val r = RingService.ringing.value
        assertNotNull(r)
        assertEquals(a.id, r!!.alarmId)
        assertNotNull(device.findObject(By.res("snooze")))
        assertNotNull(device.findObject(By.text("Fires")))

        // Snooze: back in 10 minutes, the alarm still on.
        val before = System.currentTimeMillis()
        device.findObject(By.res("snooze")).click()
        assertTrue(device.wait(Until.gone(By.res("dismiss")), 10_000))
        Thread.sleep(500)
        assertNull(RingService.ringing.value)
        val until = app.alarms.snoozedUntil(a.id)!!
        assertTrue(until - before in 590_000..610_000)
        assertTrue(app.alarms.get(a.id)!!.enabled)
        assertEquals(until, Scheduler.nextAlarmClock(ctx))
    }

    @Test fun dismissTurnsAOneOffAlarmOffAndARepeatingOneGoesToItsNextDay() {
        val app = App.instance
        val once = app.alarms.put(Alarm(Alarm.newId(), 3, 0, label = "Once"))
        screenOff()
        RingService.ringAlarm(ctx, once.id, System.currentTimeMillis())
        device.wait(Until.findObject(By.res("dismiss")), 20_000)!!.click()
        assertTrue(device.wait(Until.gone(By.res("dismiss")), 10_000))
        Thread.sleep(500)
        assertFalse(app.alarms.get(once.id)!!.enabled)

        val daily = app.alarms.put(Alarm(Alarm.newId(), 3, 0, Days.order.toSet(), label = "Daily"))
        screenOff()
        RingService.ringAlarm(ctx, daily.id, System.currentTimeMillis())
        device.wait(Until.findObject(By.res("dismiss")), 20_000)!!.click()
        assertTrue(device.wait(Until.gone(By.res("dismiss")), 10_000))
        Thread.sleep(500)
        assertTrue(app.alarms.get(daily.id)!!.enabled)
        val next = Scheduler.nextAlarmClock(ctx)!!
        assertEquals(Schedule.nextRing(daily, ZonedDateTime.now())!!.toInstant().toEpochMilli(), next)
    }

    @Test fun theTimerRingsAndPlusOneMinuteRestartsIt() {
        val app = App.instance
        screenOff()
        app.timer.update("test") { s, n -> s.start(2_000, n) }
        val stop = device.wait(Until.findObject(By.res("dismiss")), 30_000)
        assertNotNull("the timer never rang", stop)
        assertEquals("timer", RingService.ringing.value?.kind)
        assertTrue(app.timer.state.value.idle)
        device.findObject(By.res("timer-plus")).click()
        assertTrue(device.wait(Until.gone(By.res("dismiss")), 10_000))
        Thread.sleep(500)
        assertTrue(app.timer.state.value.running)
        assertTrue(app.timer.state.value.left(System.currentTimeMillis()) in 50_000..60_000)
        app.timer.update("test") { s, n -> s.cancel(n) }
    }
}

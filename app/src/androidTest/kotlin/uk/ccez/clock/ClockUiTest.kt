package uk.ccez.clock

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The three tabs, the editor and settings, through the real screens. Silent, and never syncs. */
@RunWith(AndroidJUnit4::class)
class ClockUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic @BeforeClass fun quiet() = Fixtures.quiet()
    }

    @Before fun clean() {
        Fixtures.clear()
        rule.onNodeWithTag("tab-alarm").performClick()
    }

    @Test fun addAnAlarmWithDaysAndALabel() {
        rule.onNodeWithTag("add").performClick()
        rule.onNodeWithTag("editor").assertIsDisplayed()
        rule.onNodeWithTag("day-mon").performScrollTo().performClick()
        rule.onNodeWithTag("day-wed").performClick()
        rule.onNodeWithTag("label").performScrollTo().performTextInput("Gym")
        rule.onNodeWithTag("save").performClick()
        rule.onNodeWithTag("alarm-tab").assertIsDisplayed()
        val a = App.instance.alarms.alarms.value.single()
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), a.days)
        assertEquals("Gym", a.label)
        assertTrue(a.enabled)
        rule.onNodeWithTag("alarm:${a.id}").assertIsDisplayed()
        // The header tells when it rings next.
        rule.onNode(SemanticsMatcher("next ring") { n -> n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { t -> t.text.startsWith("In ") } == true }).assertIsDisplayed()
    }

    @Test fun rowsReadWellToTalkBackAndToggle() {
        val a = App.instance.alarms.put(Alarm("t1", 7, 30, Days.weekdays, true, "Gym"))
        rule.waitForIdle()
        rule.onNodeWithContentDescription("7:30 AM, Weekdays, Gym").assertIsDisplayed()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On").and(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "toggle:${a.id}")))
            .performClick()
        rule.waitForIdle()
        assertFalse(App.instance.alarms.get(a.id)!!.enabled)
        rule.onNodeWithTag("toggle:${a.id}").performClick()
        rule.waitForIdle()
        assertTrue(App.instance.alarms.get(a.id)!!.enabled)
    }

    @Test fun editThenDelete() {
        val a = App.instance.alarms.put(Alarm("t2", 6, 0, label = "Early"))
        rule.waitForIdle()
        rule.onNodeWithContentDescription("6:00 AM, Once, Early").performClick()
        rule.onNodeWithTag("editor").assertIsDisplayed()
        rule.onNodeWithTag("delete").performScrollTo().performClick()
        rule.waitForIdle()
        assertNull(App.instance.alarms.get(a.id))
        rule.onNodeWithText("No alarms").assertIsDisplayed()
    }

    @Test fun stopwatchStartLapStopReset() {
        rule.onNodeWithTag("tab-stopwatch").performClick()
        rule.onNodeWithTag("start").performClick()
        Thread.sleep(300)
        rule.onNodeWithTag("lap").performClick()
        rule.onNodeWithTag("lap").performClick()
        rule.onNodeWithTag("stop").performClick()
        rule.waitForIdle()
        val s = App.instance.stopwatch.state.value
        assertFalse(s.running)
        assertEquals(2, s.laps.size)
        assertTrue(s.banked >= 300)
        rule.onNodeWithTag("laps").assertIsDisplayed()
        rule.onNodeWithTag("reset").performClick()
        rule.waitForIdle()
        assertEquals(StopwatchState(), App.instance.stopwatch.state.value)
    }

    @Test fun timerKeypadPresetPauseCancel() {
        rule.onNodeWithTag("tab-timer").performClick()
        rule.onNodeWithTag("timer-start").assertIsNotEnabled()
        rule.onNodeWithTag("key-1").performClick()
        rule.onNodeWithTag("key-3").performClick()
        rule.onNodeWithTag("key-0").performClick()
        rule.onNodeWithContentDescription("1 minute 30 seconds").assertIsDisplayed()
        rule.onNodeWithTag("key-Delete").performClick()
        rule.onNodeWithContentDescription("13 seconds").assertIsDisplayed()
        rule.onNodeWithTag("preset-5").performClick()
        rule.onNodeWithTag("timer-running").assertIsDisplayed()
        assertTrue(App.instance.timer.state.value.running)
        assertEquals(300_000, App.instance.timer.state.value.total)
        rule.onNodeWithTag("timer-pause").performClick()
        rule.onNodeWithTag("timer-resume").assertIsDisplayed()
        assertTrue(App.instance.timer.state.value.paused)
        rule.onNodeWithTag("timer-add").performClick()
        assertEquals(360_000, App.instance.timer.state.value.total)
        rule.onNodeWithTag("timer-cancel").performClick()
        rule.onNodeWithTag("timer-tab").assertIsDisplayed()
        assertTrue(App.instance.timer.state.value.idle)
    }

    @Test fun settingsShowThePhoneChecks() {
        rule.onNodeWithTag("settings").performClick()
        rule.onNodeWithTag("settings-screen").assertIsDisplayed()
        rule.onNodeWithTag("check-exact_alarms").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("check-full_screen").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("back").performClick()
        rule.onNodeWithTag("alarm-tab").assertIsDisplayed()
    }
}

object Fixtures {
    /** Silent and offline: no sound previews, no sync to the owner's server. */
    fun quiet() {
        Testing.quiet = true
        val ui = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        for (cmd in listOf(
            "pm grant uk.ccez.clock android.permission.POST_NOTIFICATIONS",
            "appops set uk.ccez.clock USE_FULL_SCREEN_INTENT allow",
            "cmd deviceidle whitelist +uk.ccez.clock",
            // The no-audio emulator's media stack stalls system processes; their dialogs would cover the screens.
            "settings put global hide_error_dialogs 1",
        )) ui.executeShellCommand(cmd).close()
        App.instance.settings.set("sync.server", "\"\"")
        App.instance.settings.set("timer.presets", "1,3,5,10,15,30")
    }

    fun clear() {
        val app = App.instance
        for (a in app.alarms.alarms.value) app.alarms.delete(a.id)
        app.timer.update("test-reset") { s, n -> s.cancel(n) }
        app.stopwatch.update { it.reset() }
    }
}

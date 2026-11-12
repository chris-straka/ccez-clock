package uk.ccez.clock

import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId

/**
 * The ring screen, over the lock screen: the time, the label, and two
 * big buttons (Snooze above, Dismiss below; Timer: +1 min and Stop).
 * TalkBack hears "Alarm, 7:30 AM, Gym" as it opens. Back does nothing,
 * so a pocket can't dismiss it.
 */
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 27) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
        setContent {
            ClockTheme {
                val r by RingService.ringing.collectAsState()
                LaunchedEffect(r) { if (r == null) finish() }
                r?.let { RingScreen(it) }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable fun RingScreen(r: Ring) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val now = rememberNow(1000)
    val t = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    val h24 = DateFormat.is24HourFormat(ctx)
    val (time, ap) = Schedule.timeParts(t.hour, t.minute, h24)
    val timer = r.kind == "timer"
    val title = if (timer) "Timer" else "Alarm"
    val spoken = listOf(if (timer) "Time's up" else title, Schedule.timeText(t.hour, t.minute, h24), r.label).filter { it.isNotEmpty() }.joinToString(", ")
    val view = LocalView.current
    LaunchedEffect(r.started) {
        @Suppress("DEPRECATION")
        view.announceForAccessibility(spoken)
    }
    Box(
        Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding().semantics {
            testTagsAsResourceId = true
            paneTitle = title
        }.testTag("ring"),
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Gap(48)
                Text(if (timer) "Time's up" else title, color = Palette.dim, fontSize = 22.sp)
                Text(
                    if (ap.isEmpty()) time else "$time $ap",
                    color = Palette.fg,
                    fontSize = 80.sp,
                    lineHeight = 88.sp,
                    style = Tabular,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("ring-time").semantics {
                        contentDescription = spoken
                        liveRegion = LiveRegionMode.Assertive
                    },
                )
                if (r.label.isNotEmpty()) Text(r.label, color = Palette.fg, fontSize = 26.sp, textAlign = TextAlign.Center, modifier = Modifier.testTag("ring-label"))
            }
            if (timer) {
                Pill("+1 min", { RingService.send(ctx, RingService.TIMER_ADD) }, Modifier.fillMaxWidth().testTag("timer-plus"), height = 96.dp, fontSize = 24)
                Gap(16)
                Pill("Stop", { RingService.send(ctx, RingService.DISMISS) }, Modifier.fillMaxWidth().testTag("dismiss"), filled = true, height = 120.dp, fontSize = 28)
            } else {
                Pill("Snooze ${r.snooze} min", { RingService.send(ctx, RingService.SNOOZE) }, Modifier.fillMaxWidth().testTag("snooze"), height = 96.dp, fontSize = 24)
                Gap(16)
                Pill("Dismiss", { RingService.send(ctx, RingService.DISMISS) }, Modifier.fillMaxWidth().testTag("dismiss"), filled = true, height = 120.dp, fontSize = 28)
            }
            Gap(12)
        }
    }
}

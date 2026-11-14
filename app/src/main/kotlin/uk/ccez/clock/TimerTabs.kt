package uk.ccez.clock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

private fun sw() = Triple(App.elapsed(), System.currentTimeMillis(), App.bootCount(App.instance))

@Composable fun StopwatchTab() {
    val store = App.instance.stopwatch
    val s by store.state.collectAsState()
    // Ticks only while running and on screen; nothing runs in the background.
    val shown by produceState(0L, s) {
        while (true) {
            val (e, w, b) = sw()
            value = s.elapsed(e, w, b)
            if (!s.running) break
            // 30 a second: hundredths still move, and Compose tests can go idle.
            kotlinx.coroutines.delay(33)
        }
    }
    val started = s.running || s.banked > 0
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("stopwatch-tab"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().weight(if (s.laps.isEmpty()) 1f else 0.6f), contentAlignment = Alignment.Center) {
            FitText(
                Durations.stopwatch(shown),
                Palette.fg,
                60,
                style = Tabular,
                modifier = Modifier.testTag("stopwatch-time").semantics {
                    contentDescription = Durations.spoken(shown) + if (s.running) ", running" else ""
                },
            )
        }
        if (s.laps.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("laps")) {
                items(s.lapRows(), key = { it.first }) { (n, lap, total) ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                            .semantics(mergeDescendants = true) { contentDescription = "Lap $n, ${Durations.spoken(lap)}" },
                    ) {
                        FitText("$n", Palette.dim, 17, Modifier.weight(0.4f), align = TextAlign.Start)
                        FitText(Durations.stopwatch(lap), Palette.fg, 17, Modifier.weight(1f), style = Tabular, align = TextAlign.End)
                        FitText(Durations.stopwatch(total), Palette.dim, 17, Modifier.weight(1f).padding(start = 8.dp), style = Tabular, align = TextAlign.End)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                s.running -> {
                    Pill(Glyph.LAP, "Lap", { val (e, w, b) = sw(); store.update { it.lap(e, w, b) } }, Modifier.weight(1f).testTag("lap"))
                    Pill(Glyph.PAUSE, "Stop", { val (e, w, b) = sw(); store.update { it.stop(e, w, b) } }, Modifier.weight(1f).testTag("stop"), filled = true)
                }
                started -> {
                    Pill(Glyph.RESET, "Reset", { store.update { it.reset() } }, Modifier.weight(1f).testTag("reset"))
                    Pill(Glyph.PLAY, "Resume", { val (e, w, b) = sw(); store.update { it.start(e, w, b) } }, Modifier.weight(1f).testTag("start"), filled = true)
                }
                else -> Pill(Glyph.PLAY, "Start", { val (e, w, b) = sw(); store.update { it.start(e, w, b) } }, Modifier.weight(1f).testTag("start"), filled = true)
            }
        }
    }
}

@Composable fun TimerTab() {
    val app = App.instance
    val t by app.timer.state.collectAsState()
    if (t.idle) TimerSetup() else TimerRunning(t)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun TimerSetup() {
    val app = App.instance
    var digits by rememberSaveable { mutableStateOf("") }
    val ms = Durations.fromDigits(digits)
    val presets = runCatching { SettingDefs.presets(app.settings.text("timer.presets")) }.getOrDefault(emptyList())
    val d = digits.takeLast(6).padStart(6, '0')
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("timer-tab")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.padding(vertical = 16.dp).testTag("timer-entry").semantics(mergeDescendants = true) {
                    contentDescription = if (ms == 0L) "No time set" else Durations.spoken(ms)
                    liveRegion = LiveRegionMode.Polite
                },
                verticalAlignment = Alignment.Bottom,
            ) {
                val col = { on: Boolean -> if (on) Palette.fg else Palette.faint }
                Text(d.substring(0, 2), color = col(digits.length > 4), fontSize = 52.sp, style = Tabular)
                Text("h ", color = Palette.faint, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp))
                Text(d.substring(2, 4), color = col(digits.length > 2), fontSize = 52.sp, style = Tabular)
                Text("m ", color = Palette.faint, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp))
                Text(d.substring(4, 6), color = col(digits.isNotEmpty()), fontSize = 52.sp, style = Tabular)
                Text("s", color = Palette.faint, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp))
            }
            if (presets.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().testTag("presets"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (p in presets) {
                        Chip("$p min", false, {
                            app.timer.update("start") { s, n -> s.start(p * 60_000L, n) }
                            digits = ""
                        }, Modifier.testTag("preset-$p"), spoken = "Start $p minute timer")
                    }
                }
            }
            Gap(16)
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", "⌫")
            for (row in keys.chunked(3)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in row) {
                        val spoken = if (k == "⌫") "Delete" else k
                        Pill(
                            k,
                            {
                                digits = when (k) {
                                    "⌫" -> digits.dropLast(1)
                                    else -> (digits + k).trimStart('0').take(6)
                                }
                            },
                            Modifier.weight(1f).testTag("key-$spoken").semantics { contentDescription = spoken },
                            height = 60.dp,
                            fontSize = 24,
                        )
                    }
                }
            }
        }
        Pill(
            Glyph.PLAY,
            "Start",
            { app.timer.update("start") { s, n -> s.start(ms, n) }; digits = "" },
            Modifier.fillMaxWidth().padding(top = 8.dp).testTag("timer-start"),
            filled = true,
            enabled = ms > 0,
        )
    }
}

@Composable private fun TimerRunning(t: TimerState) {
    val app = App.instance
    val now = if (t.running) rememberNow(250) else System.currentTimeMillis()
    val left = t.left(now)
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("timer-running"), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val side = min(maxWidth.value, maxHeight.value).dp * 0.86f
            val frac = if (t.total > 0) (left.toFloat() / t.total).coerceIn(0f, 1f) else 0f
            Canvas(Modifier.size(side)) {
                val sw = 6.dp.toPx()
                val inset = sw / 2
                drawArc(Palette.line, 0f, 360f, false, Offset(inset, inset), Size(size.width - sw, size.height - sw), style = Stroke(sw))
                drawArc(Palette.accent, -90f, 360f * frac, false, Offset(inset, inset), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FitText(
                    Durations.timer(left),
                    Palette.fg,
                    64,
                    style = Tabular,
                    modifier = Modifier.padding(horizontal = 24.dp).testTag("timer-left").semantics { contentDescription = Durations.spoken(left) + if (t.paused) ", paused" else " left" },
                )
                if (t.paused) Text("Paused", color = Palette.dim, fontSize = 18.sp)
            }
        }
        Pill("+1 min", { app.timer.update("plus-minute") { s, n -> s.addMinute(n) } }, Modifier.padding(bottom = 12.dp).testTag("timer-add"), height = 52.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill(Glyph.CLOSE, "Cancel timer", { app.timer.update("cancel") { s, n -> s.cancel(n) } }, Modifier.weight(1f).testTag("timer-cancel"))
            if (t.running) Pill(Glyph.PAUSE, "Pause", { app.timer.update("pause") { s, n -> s.pause(n) } }, Modifier.weight(1f).testTag("timer-pause"), filled = true)
            else Pill(Glyph.PLAY, "Resume", { app.timer.update("resume") { s, n -> s.resume(n) } }, Modifier.weight(1f).testTag("timer-resume"), filled = true)
        }
    }
}

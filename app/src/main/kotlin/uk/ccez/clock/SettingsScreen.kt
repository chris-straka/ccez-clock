package uk.ccez.clock

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

@Composable fun SettingsScreen(onBack: () -> Unit) {
    val app = App.instance
    val ctx = LocalContext.current
    val s = app.settings
    @Suppress("UNUSED_VARIABLE") val v by s.version.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    // The phone checks again each time the screen comes back (from system settings).
    var checksVersion by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.repeatOnLifecycle(Lifecycle.State.RESUMED) { checksVersion++ } }
    val checks = remember(checksVersion) { Health.checks(ctx) }
    val syncLast by Sync.last.collectAsState()

    Column(Modifier.fillMaxSize().testTag("settings-screen")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyph.BACK, "Back", onBack, Modifier.testTag("back"))
            Text("Settings", color = Palette.fg, fontSize = 22.sp, modifier = Modifier.semantics { heading() })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            ValueRow("Sound", Synth.name(s.text("alarm.sound")), { dialog = "alarm.sound" })
            ValueRow("Fade in", s.int("alarm.fade_seconds").let { if (it == 0) "Off" else "$it s" }, { dialog = "alarm.fade_seconds" })
            SwitchRow("Vibrate", s.bool("alarm.vibrate"), { s.set("alarm.vibrate", it.toString()) })
            ValueRow("Snooze", "${s.int("alarm.snooze_minutes")} min", { dialog = "alarm.snooze_minutes" })
            ValueRow("Ring for", "${s.int("alarm.ring_minutes")} min", { dialog = "alarm.ring_minutes" })
            ValueRow("Lowest volume", s.int("alarm.min_volume").let { if (it == 0) "Off" else "$it%" }, { dialog = "alarm.min_volume" })
            ValueRow("Timer sound", Synth.name(s.text("timer.sound")), { dialog = "timer.sound" })
            Gap(16)
            SectionTitle("Phone")
            for (c in checks) {
                ValueRow(c.label, if (c.ok) "On" else "Off", { fix(ctx, c.key) }, Modifier.testTag("check-${c.key}"))
            }
            Gap(16)
            SectionTitle("Sync")
            ValueRow("Server", Sync.server().removePrefix("https://").ifEmpty { "Off" }, { dialog = "sync.server" })
            val last = Sync.lastOk()
            ValueRow(
                "Synced",
                when {
                    Sync.server().isEmpty() -> "-"
                    syncLast == "failed" -> "Failed"
                    last > 0 -> Notifications.timeOf(ctx, last)
                    else -> "Never"
                },
                { app.scope.launch { Sync.now(ctx, "tap") } },
            )
            SwitchRow("Install updates", s.bool("updates.self"), { s.set("updates.self", it.toString()) })
            Gap(24)
        }
    }

    val d = dialog
    when {
        d == "alarm.sound" || d == "timer.sound" -> SoundDialog(s.text(d), withDefault = false, onPick = { s.set(d, it); dialog = null }, onClose = { dialog = null })
        d == "sync.server" -> TextDialog("Server", s.text(d), onDone = { runCatching { s.set(d, it) }; Sync.schedulePeriodic(ctx); dialog = null }, onClose = { dialog = null })
        d != null -> {
            val opts = when (d) {
                "alarm.fade_seconds" -> listOf(0, 10, 30, 60).map { it.toString() to if (it == 0) "Off" else "$it s" }
                "alarm.snooze_minutes" -> listOf(5, 10, 15, 20, 30).map { it.toString() to "$it min" }
                "alarm.ring_minutes" -> listOf(5, 10, 15, 30).map { it.toString() to "$it min" }
                "alarm.min_volume" -> listOf(0, 20, 40, 60, 80, 100).map { it.toString() to if (it == 0) "Off" else "$it%" }
                else -> emptyList()
            }
            val title = mapOf("alarm.fade_seconds" to "Fade in", "alarm.snooze_minutes" to "Snooze", "alarm.ring_minutes" to "Ring for", "alarm.min_volume" to "Lowest volume")[d] ?: d
            ChoiceDialog(title, opts, s.get(d).toString(), onPick = { s.set(d, it); dialog = null }, onClose = { dialog = null })
        }
    }
}

@Composable private fun SectionTitle(t: String) =
    Text(t, color = Palette.faint, fontSize = 14.sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp).semantics { heading() })

@Composable private fun TextDialog(title: String, value: String, onDone: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.cell,
        title = { Text(title, color = Palette.fg) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }) { Text("Save", color = Palette.fg) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Palette.dim) } },
    )
}

/** Opens the system page that fixes [key] (the "Phone" rows). */
private fun fix(ctx: android.content.Context, key: String) {
    val pkg = Uri.parse("package:${ctx.packageName}")
    val i = when (key) {
        "exact_alarms" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
        "full_screen" -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null
        "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        "battery" -> @Suppress("BatteryLife") Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
        "alarm_volume" -> Intent(Settings.ACTION_SOUND_SETTINGS)
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    } ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .recoverCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

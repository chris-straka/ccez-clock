package uk.ccez.clock

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** A new alarm ([id] null) or one to change: time, days, label, sound, vibrate, snooze. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AlarmEditor(id: String?, onDone: () -> Unit) {
    val app = App.instance
    val ctx = LocalContext.current
    val h24 = DateFormat.is24HourFormat(ctx)
    val old = remember(id) { id?.let { app.alarms.get(it) } }
    val start = remember(id) {
        old ?: ZonedDateTime.now().plusHours(1).let { n ->
            Alarm(Alarm.newId(), n.hour, 0, vibrate = app.settings.bool("alarm.vibrate"), snooze = app.settings.int("alarm.snooze_minutes"))
        }
    }
    val time = rememberTimePickerState(start.hour, start.minute, h24)
    var days by remember(id) { mutableStateOf(start.days) }
    var label by remember(id) { mutableStateOf(start.label) }
    var sound by remember(id) { mutableStateOf(start.sound) }
    var vibrate by remember(id) { mutableStateOf(start.vibrate) }
    var snooze by remember(id) { mutableStateOf(start.snooze) }
    var dialog by remember { mutableStateOf<String?>(null) }

    val draft = start.copy(hour = time.hour, minute = time.minute, days = days, label = label.trim().take(80), sound = sound, vibrate = vibrate, snooze = snooze, enabled = true)
    val zNow = ZonedDateTime.now()
    val ring = Schedule.nextRing(draft, zNow)

    Column(Modifier.fillMaxSize().testTag("editor")) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Gap(16)
            TimePicker(
                state = time,
                modifier = Modifier.testTag("time"),
                colors = TimePickerDefaults.colors(
                    clockDialColor = Palette.cell,
                    selectorColor = Palette.accent,
                    containerColor = Palette.bg,
                    periodSelectorBorderColor = Palette.line,
                    clockDialSelectedContentColor = Color.Black,
                    clockDialUnselectedContentColor = Palette.fg,
                    periodSelectorSelectedContainerColor = Palette.accent,
                    periodSelectorUnselectedContainerColor = Palette.bg,
                    periodSelectorSelectedContentColor = Color.Black,
                    periodSelectorUnselectedContentColor = Palette.dim,
                    timeSelectorSelectedContainerColor = Palette.accent,
                    timeSelectorUnselectedContainerColor = Palette.cell,
                    timeSelectorSelectedContentColor = Color.Black,
                    timeSelectorUnselectedContentColor = Palette.fg,
                ),
            )
            if (ring != null) Text(Schedule.untilText(zNow, ring), color = Palette.dim, fontSize = 16.sp, modifier = Modifier.semantics { contentDescription = "Rings ${Schedule.untilText(zNow, ring).lowercase()}" })
            Gap(20)
            DaysRow(days) { days = it }
            Gap(16)
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(80) },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("label"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.dim, unfocusedBorderColor = Palette.line,
                    focusedLabelColor = Palette.dim, unfocusedLabelColor = Palette.faint, cursorColor = Palette.accent,
                ),
            )
            Gap(8)
            ValueRow("Sound", Synth.name(sound.ifEmpty { app.settings.text("alarm.sound") }) + if (sound.isEmpty()) " (default)" else "", { dialog = "sound" }, Modifier.testTag("sound"))
            SwitchRow("Vibrate", vibrate, { vibrate = it }, Modifier.testTag("vibrate"))
            ValueRow("Snooze", "$snooze min", { dialog = "snooze" }, Modifier.testTag("snooze"))
            if (old != null) {
                Gap(8)
                TextButton(onClick = { app.alarms.delete(old.id); onDone() }, modifier = Modifier.defaultMinSize(minHeight = 56.dp).testTag("delete")) {
                    Text("Delete", color = Palette.dim, fontSize = 17.sp)
                }
            }
            Gap(16)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("Cancel", onDone, Modifier.weight(1f).testTag("cancel"))
            Pill("Save", { app.alarms.put(draft, if (old == null) "add" else "edit"); onDone() }, Modifier.weight(1f).testTag("save"), filled = true)
        }
    }

    when (dialog) {
        "sound" -> SoundDialog(
            selected = sound,
            withDefault = true,
            onPick = { sound = it; dialog = null },
            onClose = { dialog = null },
        )
        "snooze" -> ChoiceDialog(
            "Snooze",
            listOf(5, 10, 15, 20, 30).map { it.toString() to "$it min" },
            snooze.toString(),
            onPick = { snooze = it.toInt(); dialog = null },
            onClose = { dialog = null },
        )
    }
}

/** Seven round day buttons in the locale's week order; none on: rings once. */
@Composable fun DaysRow(days: Set<DayOfWeek>, onChange: (Set<DayOfWeek>) -> Unit) {
    val locale = Locale.getDefault()
    Row(Modifier.fillMaxWidth().testTag("days"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in Schedule.weekOrder(locale)) {
            val on = d in days
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .defaultMinSize(40.dp, 40.dp)
                    .clip(CircleShape)
                    .background(if (on) Palette.fg else Palette.cell)
                    .clickable(role = Role.Checkbox) { onChange(if (on) days - d else days + d) }
                    .semantics {
                        contentDescription = d.getDisplayName(TextStyle.FULL, locale)
                        stateDescription = if (on) "On" else "Off"
                    }
                    .testTag("day-${Days.code(d)}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(d.getDisplayName(TextStyle.NARROW, locale), color = if (on) Color.Black else Palette.fg, fontSize = 16.sp, maxLines = 1)
            }
        }
    }
}

/** Pick one of [options] (value to name). */
@Composable fun ChoiceDialog(title: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.cell,
        title = { Text(title, color = Palette.fg) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((v, name) in options) ChoiceRow(name, v == selected) { onPick(v) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = Palette.dim) } },
    )
}

@Composable fun ChoiceRow(name: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { stateDescription = if (on) "Selected" else "" }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = if (on) Palette.accent else Palette.fg, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (on) Text("✓", color = Palette.accent, fontSize = 17.sp)
    }
}

/** The sounds; a tap picks one and plays a few seconds of it (alarm volume). */
@Composable fun SoundDialog(selected: String, withDefault: Boolean, onPick: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember { mutableStateOf<Player?>(null) }
    var current by remember { mutableStateOf(selected) }
    DisposableEffect(Unit) { onDispose { player.value?.stop() } }
    fun preview(id: String) {
        player.value?.stop()
        player.value = if (id.isEmpty() || Testing.quiet) null else runCatching { Player(ctx).also { it.start(id, 0, seconds = 4) } }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.cell,
        title = { Text("Sound", color = Palette.fg) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (withDefault) ChoiceRow("Default", current.isEmpty()) { current = ""; preview(App.instance.settings.text("alarm.sound")) }
                for (s in Synth.SOUNDS) ChoiceRow(Synth.name(s), current == s, Modifier.testTag("sound-$s")) { current = s; preview(s) }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current) }, modifier = Modifier.testTag("sound-ok")) { Text("Done", color = Palette.fg) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Palette.dim) } },
    )
}

/** Set by tests: no sound previews, ever (silent tests). */
object Testing {
    @Volatile var quiet = false
}

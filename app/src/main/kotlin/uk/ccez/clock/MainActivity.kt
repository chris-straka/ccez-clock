package uk.ccez.clock

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab(val label: String) { ALARM("Alarm"), STOPWATCH("Stopwatch"), TIMER("Timer") }

/** Alarm, Stopwatch, Timer: three tabs at the bottom, as Samsung Clock has them. */
class MainActivity : ComponentActivity() {
    private var tab by mutableStateOf(Tab.ALARM)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        tabFrom(intent)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences("asked", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("notifications", false)) {
                prefs.edit().putBoolean("notifications", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        setContent { ClockTheme { Main(tab, onTab = { tab = it }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tabFrom(intent)
    }

    private fun tabFrom(i: Intent?) {
        when (i?.getStringExtra("tab")) {
            "timer" -> tab = Tab.TIMER
            "stopwatch" -> tab = Tab.STOPWATCH
            "alarm" -> tab = Tab.ALARM
        }
    }

    override fun onStop() {
        App.instance.visible = false
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        val app = App.instance
        app.visible = true
        // Self-healing: whatever happened while closed, the schedule is redone.
        app.scope.launch {
            Scheduler.scheduleAll(app, "open")
            Scheduler.scheduleTimer(app)
            Sync.schedulePeriodic(app)
            Sync.now(app, "open")
        }
        Updater.maybe(this)
    }
}

sealed class Page {
    data object Tabs : Page()
    data class Edit(val id: String?) : Page()
    data object Settings : Page()
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable fun Main(tab: Tab, onTab: (Tab) -> Unit) {
    var page by rememberSaveableHolder()
    Box(
        Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding().semantics { testTagsAsResourceId = true },
    ) {
        when (val p = page) {
            is Page.Edit -> {
                BackHandler { page = Page.Tabs }
                AlarmEditor(p.id, onDone = { page = Page.Tabs })
            }
            Page.Settings -> {
                BackHandler { page = Page.Tabs }
                SettingsScreen(onBack = { page = Page.Tabs })
            }
            Page.Tabs -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.ALARM -> AlarmTab(onEdit = { page = Page.Edit(it) }, onSettings = { page = Page.Settings })
                        Tab.STOPWATCH -> StopwatchTab()
                        Tab.TIMER -> TimerTab()
                    }
                }
                TabBar(tab, onTab)
            }
        }
    }
}

/** The page survives rotation (ids are strings, so a tiny saver). */
@Composable private fun rememberSaveableHolder() = rememberSaveable(
    saver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.MutableState<Page>, String>(
        save = {
            when (val p = it.value) {
                Page.Tabs -> "tabs"
                Page.Settings -> "settings"
                is Page.Edit -> "edit:${p.id ?: ""}"
            }
        },
        restore = { s ->
            mutableStateOf(
                when {
                    s == "settings" -> Page.Settings
                    s.startsWith("edit:") -> Page.Edit(s.removePrefix("edit:").ifEmpty { null })
                    else -> Page.Tabs
                },
            )
        },
    ),
) { mutableStateOf<Page>(Page.Tabs) }

@Composable fun TabBar(tab: Tab, onTab: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        for (t in Tab.entries) {
            val on = t == tab
            Box(
                Modifier
                    // Width by label length, so all three keep one size at large fonts.
                    .weight(if (t == Tab.STOPWATCH) 1.5f else 1f)
                    .defaultMinSize(minHeight = 64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Tab) { onTab(t) }
                    .semantics { selected = on }
                    .testTag("tab-${t.name.lowercase()}"),
                contentAlignment = Alignment.Center,
            ) {
                FitText(t.label, if (on) Palette.fg else Palette.faint, 16, Modifier.padding(horizontal = 4.dp), weight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** Now, refreshed every [everyMs]. */
@Composable fun rememberNow(everyMs: Long = 1000): Long {
    val v by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(everyMs - System.currentTimeMillis() % everyMs)
        }
    }
    return v
}

@Composable fun AlarmTab(onEdit: (String?) -> Unit, onSettings: () -> Unit) {
    val app = App.instance
    val alarms by app.alarms.alarms.collectAsState()
    val snoozed by app.alarms.snoozed.collectAsState()
    val now = rememberNow(15_000)
    val h24 = DateFormat.is24HourFormat(LocalContext.current)
    val zNow = ZonedDateTime.now()
    val next = alarms.mapNotNull { a -> Schedule.nextRing(a, snoozed[a.id], zNow)?.let { a to it } }.minByOrNull { it.second }
    Column(Modifier.fillMaxSize().testTag("alarm-tab")) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            GlyphButton(Glyph.PLUS, "Add alarm", { onEdit(null) }, Modifier.testTag("add"))
            GlyphButton(Glyph.MORE, "Settings", onSettings, Modifier.testTag("settings"))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp).semantics(mergeDescendants = true) { heading() }) {
            if (next != null) {
                Text(Schedule.untilText(zNow, next.second), color = Palette.fg, fontSize = 28.sp, lineHeight = 34.sp)
                val (t, ap) = Schedule.timeParts(next.second.hour, next.second.minute, h24)
                val day = next.second.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                Text("$day $t${if (ap.isEmpty()) "" else " $ap"}", color = Palette.dim, fontSize = 16.sp)
            } else {
                Text(if (alarms.isEmpty()) "No alarms" else "All alarms off", color = Palette.dim, fontSize = 28.sp, lineHeight = 34.sp)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().testTag("alarm-list"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        ) {
            items(alarms, key = { it.id }) { a ->
                AlarmRow(a, snoozed[a.id]?.takeIf { it > now }, h24, onClick = { onEdit(a.id) }, onToggle = { app.alarms.setEnabled(a.id, it) })
            }
        }
    }
}

@Composable fun AlarmRow(a: Alarm, snoozedUntil: Long?, h24: Boolean, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val (t, ap) = Schedule.timeParts(a.hour, a.minute, h24)
    val col = if (a.enabled) Palette.fg else Palette.faint
    val ctx = LocalContext.current
    val sub = listOfNotNull(Schedule.daysText(a.days), a.label.ifEmpty { null }).joinToString(" · ")
    val spoken = listOfNotNull(Schedule.timeText(a.hour, a.minute, h24), Schedule.daysSpoken(a.days), a.label.ifEmpty { null }).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.cell)
            .testTag("alarm:${a.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(onClickLabel = "Edit", onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .padding(start = 22.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(t, color = col, fontSize = 44.sp, lineHeight = 48.sp, style = Tabular)
                if (ap.isNotEmpty()) Text(" $ap", color = col, fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(sub, color = if (a.enabled) Palette.dim else Palette.faint, fontSize = 15.sp)
            if (snoozedUntil != null) Text("Snoozed until ${Notifications.timeOf(ctx, snoozedUntil)}", color = Palette.accent, fontSize = 15.sp)
        }
        Switch(
            checked = a.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier
                .padding(end = 18.dp)
                .semantics {
                    contentDescription = "Alarm ${Schedule.timeText(a.hour, a.minute, h24)}"
                    stateDescription = if (a.enabled) "On" else "Off"
                }
                .testTag("toggle:${a.id}"),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Palette.accent,
                uncheckedThumbColor = Palette.dim,
                uncheckedTrackColor = Palette.line,
                uncheckedBorderColor = Palette.line,
            ),
        )
    }
}

/** A row in settings or the editor: a name, its value, tap to change. */
@Composable fun ValueRow(title: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Palette.fg, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, color = Palette.dim, fontSize = 17.sp)
    }
}

@Composable fun SwitchRow(title: String, on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Switch, onClickLabel = if (on) "Turn off" else "Turn on") { onChange(!on) }
            .semantics(mergeDescendants = true) { stateDescription = if (on) "On" else "Off" }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Palette.fg, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Palette.accent,
                uncheckedThumbColor = Palette.dim,
                uncheckedTrackColor = Palette.line,
                uncheckedBorderColor = Palette.line,
            ),
        )
    }
}

@Composable fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

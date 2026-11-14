package uk.ccez.clock

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sync with the clock server on the tailnet (server/ in this repo, on z):
 * one POST /sync sends what changed here (alarms, the timer) and the
 * phone's state, and brings back what changed there since the last
 * cursor. The later change wins, per alarm, by `at` (as ccez-words).
 *
 * When: the app opening, a change here (after 1.5 s), every 15 minutes
 * (JobScheduler, network required), and 3 minutes before each ring
 * (Scheduler's pre-ring wake-up).
 */
object Sync {
    private val lock = Mutex()
    private var pending: Job? = null

    /** Last result, for the settings screen and `clock.ts status`. */
    val last = MutableStateFlow<String>("")

    fun server(): String = App.instance.settings.text("sync.server").trim().trimEnd('/')

    fun soon(ctx: Context, waitMs: Long = 1500) {
        if (server().isEmpty()) return
        val app = App.instance
        synchronized(this) {
            pending?.cancel()
            pending = app.scope.launch {
                delay(waitMs)
                now(ctx, "change")
            }
        }
    }

    suspend fun now(ctx: Context, why: String): Boolean {
        val base = server()
        if (base.isEmpty()) return false
        val app = App.instance
        return lock.withLock {
            val started = System.currentTimeMillis()
            val prefs = app.safe.getSharedPreferences("sync", Context.MODE_PRIVATE)
            val since = prefs.getLong("seq", 0)
            val alarms = app.alarms.dirtyAlarms()
            val timer = app.timer.state.value
            val timerDirty = app.timer.dirty
            val body = JSONObject()
                .put("since", since)
                .put("alarms", JSONArray(alarms.map { it.toJson() }))
                .put("state", state(ctx))
            if (timerDirty) body.put("timer", timer.toJson())
            try {
                val c = (URL("$base/sync").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    token()?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                if (code != 200) error("HTTP $code ${c.errorStream?.bufferedReader()?.readText()?.take(200)}")
                val res = JSONObject(c.inputStream.bufferedReader().readText())
                app.alarms.pushed(alarms)
                if (timerDirty) app.timer.pushed(timer)
                val got = res.optJSONArray("alarms") ?: JSONArray()
                val remote = (0 until got.length()).mapNotNull { i ->
                    runCatching { Alarm.parse(got.getJSONObject(i)) }
                        .onFailure { app.log.log("warn", "sync-bad-alarm", "error" to it.toString()) }.getOrNull()
                }
                val changed = app.alarms.applyRemote(remote)
                res.optJSONObject("timer")?.let { t -> runCatching { app.timer.applyRemote(TimerState.parse(t)) } }
                val db = res.optLong("db", 0)
                if (SyncCursor.newDatabase(prefs.getLong("db", 0), db)) {
                    // The server's database was made anew: its count starts again
                    // below this phone's cursor, and it has lost this phone's alarms.
                    app.log.log("warn", "sync-new-server-db", "was" to prefs.getLong("db", 0), "now" to db, "seq" to since)
                    prefs.edit().putLong("seq", 0).putLong("db", db).putLong("ok", System.currentTimeMillis()).apply()
                    app.alarms.markAllDirty()
                } else {
                    prefs.edit().putLong("seq", res.optLong("seq", since)).putLong("db", db).putLong("ok", System.currentTimeMillis()).apply()
                }
                app.log.log("info", "sync", "why" to why, "sent" to alarms.size, "got" to remote.size, "changed" to changed, "timer_sent" to timerDirty, "ms" to System.currentTimeMillis() - started)
                last.value = "ok"
                true
            } catch (e: Exception) {
                app.log.log("warn", "sync-failed", "why" to why, "error" to e.toString(), "ms" to System.currentTimeMillis() - started)
                last.value = "failed"
                false
            }
        }
    }

    private fun token(): String? = runCatching {
        java.io.File(App.instance.safe.filesDir, "clock/token").readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun lastOk(): Long = App.instance.safe.getSharedPreferences("sync", Context.MODE_PRIVATE).getLong("ok", 0)

    /** What the phone reports each sync (`clock.ts phone` reads it from the server). */
    fun state(ctx: Context): JSONObject {
        val app = App.instance
        val next = Scheduler.nextAlarmClock(ctx)
        return JSONObject()
            .put("device", app.settings.text("sync.device").ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}" })
            .put("version", BuildConfig.VERSION_NAME)
            .put("at", System.currentTimeMillis())
            .put("zone", java.time.ZoneId.systemDefault().id)
            // The phone's own offset now: agents work out "+5 min" with it, whatever their time-zone rules say.
            .put("offset_min", java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000)
            .put("next", next?.let { Scheduler.iso(it) } ?: JSONObject.NULL)
            .put("ringing", RingService.ringing.value?.let { JSONObject().put("kind", it.kind).put("id", it.alarmId).put("ids", JSONArray(it.alarmIds)) } ?: JSONObject.NULL)
            .put("health", Health.json(ctx))
            .put("settings", JSONObject(app.settings.toJson()))
    }

    private const val JOB_ID = 7101

    /** Every 15 minutes (the floor), with a network; survives reboots. */
    fun schedulePeriodic(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (server().isEmpty()) {
            js.cancel(JOB_ID)
            return
        }
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, SyncJob::class.java))
            .setPeriodic(15 * 60_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        js.schedule(job)
    }
}

/** The sync cursor's rules, apart from the network (unit-tested). */
object SyncCursor {
    /** True when the server answered from another database than last time (0: not known yet). */
    fun newDatabase(storedDb: Long, gotDb: Long): Boolean = storedDb != 0L && gotDb != 0L && storedDb != gotDb
}

class SyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        App.instance.scope.launch {
            val ok = Sync.now(this@SyncJob, "periodic")
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}

/** What the phone needs for alarms to always ring (PLAN.md, "What the phone needs"). */
object Health {
    data class Check(val key: String, val label: String, val ok: Boolean)

    fun checks(ctx: Context): List<Check> {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val audio = ctx.getSystemService(AudioManager::class.java)
        return listOf(
            Check("exact_alarms", "Exact alarms", Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()),
            Check("full_screen", "Full-screen alarms", Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()),
            Check("notifications", "Notifications", nm.areNotificationsEnabled()),
            Check("battery", "Battery unrestricted", pm.isIgnoringBatteryOptimizations(ctx.packageName)),
            Check("background", "Background allowed", Build.VERSION.SDK_INT < 28 || !am.isBackgroundRestricted),
            Check("alarm_volume", "Alarm volume on", audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0),
        )
    }

    /**
     * The checks worth a line on the Alarm tab: anything off that can keep
     * an alarm from ringing. A muted alarm volume isn't one while the ring
     * raises it (`alarm.min_volume`).
     */
    fun warnings(checks: List<Check>, minVolume: Int): List<Check> =
        checks.filter { !it.ok && !(it.key == "alarm_volume" && minVolume > 0) }

    fun json(ctx: Context): JSONObject = JSONObject().also { o ->
        for (c in checks(ctx)) o.put(c.key, c.ok)
        val audio = ctx.getSystemService(AudioManager::class.java)
        o.put("alarm_volume_pct", audio.getStreamVolume(AudioManager.STREAM_ALARM) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1))
    }
}

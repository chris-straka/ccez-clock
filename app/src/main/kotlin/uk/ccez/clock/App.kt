package uk.ccez.clock

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The clock's stores. Everything lives in device-protected storage, so
 * alarms ring after a restart before the first unlock (the S24 restarts
 * itself at night by default).
 */
class App : Application() {
    init {
        instance = this
    }

    val safe: Context by lazy { createDeviceProtectedStorageContext() }
    private val dir by lazy { File(safe.filesDir, "clock").also { it.mkdirs() } }

    val log by lazy { ClockLog(dir) }
    val settings by lazy { ClockSettings(dir, log) }
    val alarms by lazy { AlarmStore(dir, log).also { s -> s.onChange = { why -> alarmsChanged(why) } } }
    val timer by lazy { TimerStore(dir, log).also { s -> s.onChange = { why -> timerChanged(why) } } }
    val stopwatch by lazy { StopwatchStore(dir) }

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            runCatching { log.log("error", "job-failed", "error" to e.toString()) }
        },
    )

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { log.log("error", "crash", "thread" to t.name, "error" to e.toString()) }
            previous?.uncaughtException(t, e)
        }
        Notifications.channels(this)
    }

    /** Any alarm change, from anywhere: the AlarmManager follows, then the server. */
    private fun alarmsChanged(why: String) {
        Scheduler.scheduleAll(this, why)
        if (why != "sync") Sync.soon(this)
    }

    private fun timerChanged(why: String) {
        Scheduler.scheduleTimer(this)
        Notifications.timer(this)
        if (why != "sync") Sync.soon(this)
    }

    /** The clock app is on screen (the ring screen then opens straight away, not as a heads-up). */
    @Volatile var visible = false

    val unlocked: Boolean get() = getSystemService(UserManager::class.java).isUserUnlocked

    companion object {
        lateinit var instance: App
            private set

        fun bootCount(ctx: Context): Int =
            runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(0)

        fun elapsed() = SystemClock.elapsedRealtime()
    }
}

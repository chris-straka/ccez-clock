package uk.ccez.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The AlarmManager side: each alarm (or its snooze) as its own
 * setAlarmClock, the timer as an exact wake-up on the elapsed-time clock,
 * and a server sync a little before the next ring (so an alarm an agent
 * moved or cancelled is honoured).
 */
object Scheduler {
    const val ACTION_ALARM = "uk.ccez.clock.ALARM"
    const val ACTION_TIMER = "uk.ccez.clock.TIMER"
    const val ACTION_PRESYNC = "uk.ccez.clock.PRESYNC"
    const val EXTRA_ID = "id"
    const val EXTRA_DUE = "due"
    private const val PRESYNC_BEFORE = 3 * 60_000L

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun canExact(ctx: Context) = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    private fun flags(update: Boolean = true) = PendingIntent.FLAG_IMMUTABLE or (if (update) PendingIntent.FLAG_UPDATE_CURRENT else 0)

    private fun alarmIntent(ctx: Context, id: String, due: Long = 0): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_ALARM)
            .setData(Uri.parse("ccez-clock://alarm/$id"))
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_DUE, due)
        return PendingIntent.getBroadcast(ctx, 0, i, flags())
    }

    private fun showIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags(),
    )

    private fun simpleIntent(ctx: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(ctx, 0, Intent(ctx, AlarmReceiver::class.java).setAction(action), flags())

    /** The next ring of every alarm, as of now; returns the soonest (id, ms). */
    fun scheduleAll(ctx: Context, why: String): Pair<String, Long>? {
        val app = App.instance
        val now = ZonedDateTime.now()
        val prefs = app.safe.getSharedPreferences("scheduled", Context.MODE_PRIVATE)
        val before = prefs.getStringSet("ids", emptySet()).orEmpty()
        val set = HashSet<String>()
        var soonest: Pair<String, Long>? = null
        val exact = canExact(ctx)
        for (a in app.alarms.everything()) {
            val next = Schedule.nextRing(a, app.alarms.snoozedUntil(a.id), now)
            if (next == null) {
                am(ctx).cancel(alarmIntent(ctx, a.id))
                continue
            }
            val ms = next.toInstant().toEpochMilli()
            val pi = alarmIntent(ctx, a.id, ms)
            if (exact) am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(ms, showIntent(ctx)), pi)
            else am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            set += a.id
            if (soonest == null || ms < soonest.second) soonest = a.id to ms
        }
        for (gone in before - set) am(ctx).cancel(alarmIntent(ctx, gone))
        prefs.edit().putStringSet("ids", set).apply()
        Notifications.showSnoozes(ctx, app.alarms.shownSnoozes(RingService.now.value?.alarmIds.orEmpty(), System.currentTimeMillis()))

        // The next server sync wake-up: 3 min before the next ring (a moved or
        // cancelled alarm is honoured) or every sync.minutes (an agent's new
        // alarm lands even in deep Doze, where jobs wait hours), whichever
        // is first. Exact and allowed while idle: it fires through Doze.
        val pre = simpleIntent(ctx, ACTION_PRESYNC)
        val nowMs = System.currentTimeMillis()
        val syncAt = listOfNotNull(
            soonest?.second?.minus(PRESYNC_BEFORE)?.takeIf { it > nowMs + 30_000 },
            app.settings.int("sync.minutes").takeIf { it > 0 }?.let { nowMs + it * 60_000L },
        ).minOrNull()
        if (syncAt != null && app.settings.text("sync.server").isNotBlank()) {
            if (exact) am(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, syncAt, pre)
            else am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, syncAt, pre)
        } else {
            am(ctx).cancel(pre)
        }
        app.log.log(
            if (exact) "info" else "error", "scheduled",
            "why" to why, "alarms" to set.size, "exact" to exact,
            "next" to soonest?.first, "at" to soonest?.second?.let { iso(it) },
        )
        return soonest
    }

    /**
     * The running timer's wake-up; a timer already over (synced late, a
     * reboot) rings now if it's recent. Always through an exact wake-up,
     * never by starting the ring here: from a sync job in the background,
     * Android may refuse the foreground service, while an exact alarm's
     * broadcast is allowed to start it.
     */
    fun scheduleTimer(ctx: Context) {
        val app = App.instance
        val t = app.timer.state.value
        val pi = simpleIntent(ctx, ACTION_TIMER)
        if (!t.running) {
            am(ctx).cancel(pi)
            return
        }
        val now = System.currentTimeMillis()
        val wake = Schedule.timerWake(t.endsAt, now)
        if (wake == null) {
            app.log.log("warn", "timer-missed", "ended" to iso(t.endsAt), "late_ms" to now - t.endsAt)
            app.timer.update("missed") { s, n -> s.cancel(n) }
            return
        }
        if (wake <= now) app.log.log("info", "timer-late", "ended" to iso(t.endsAt), "late_ms" to now - t.endsAt)
        val at = android.os.SystemClock.elapsedRealtime() + (wake - now)
        if (canExact(ctx)) am(ctx).setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        else am(ctx).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
    }

    /** When the system will ring next for us (what `clock.ts status` shows). */
    fun nextAlarmClock(ctx: Context): Long? = am(ctx).nextAlarmClock
        ?.takeIf { it.showIntent?.creatorPackage == ctx.packageName }?.triggerTime

    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
}

/** The wake-ups: an alarm, the timer, the sync before a ring, notification buttons. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = App.instance
        when (intent.action) {
            Scheduler.ACTION_ALARM -> {
                Wake.hold(ctx, 30_000)
                val id = intent.getStringExtra(Scheduler.EXTRA_ID) ?: return
                app.log.log("info", "fire", "id" to id, "due" to intent.getLongExtra(Scheduler.EXTRA_DUE, 0).let { if (it > 0) Scheduler.iso(it) else null }, "late_ms" to (System.currentTimeMillis() - intent.getLongExtra(Scheduler.EXTRA_DUE, System.currentTimeMillis())))
                RingService.ringAlarm(ctx, id, intent.getLongExtra(Scheduler.EXTRA_DUE, System.currentTimeMillis()))
            }
            Scheduler.ACTION_TIMER -> {
                Wake.hold(ctx, 30_000)
                RingService.ringTimer(ctx)
            }
            Scheduler.ACTION_PRESYNC -> {
                val done = goAsync()
                app.scope.launch {
                    try {
                        withTimeoutOrNull(20_000) { Sync.now(ctx, "wake") }
                    } finally {
                        // The next wake-up (a sync that changed alarms already did this).
                        Scheduler.scheduleAll(ctx, "sync-wake")
                        done.finish()
                    }
                }
            }
            Notifications.ACTION_SNOOZE_DISMISS -> {
                val id = intent.getStringExtra(Scheduler.EXTRA_ID) ?: return
                val until = intent.getLongExtra(Notifications.EXTRA_UNTIL, -1)
                val ended = app.alarms.dismissSnooze(id, until)
                app.log.log("info", if (ended) "snooze-dismissed" else "snooze-dismiss-stale", "id" to id, "until" to until)
                // A stale button: the shade catches up with the store.
                if (!ended) Scheduler.scheduleAll(ctx, "snooze-dismiss-stale")
            }
            Notifications.ACTION_TIMER_PAUSE -> app.timer.update("pause") { s, n -> s.pause(n) }
            Notifications.ACTION_TIMER_RESUME -> app.timer.update("resume") { s, n -> s.resume(n) }
            Notifications.ACTION_TIMER_CANCEL -> app.timer.update("cancel") { s, n -> s.cancel(n) }
        }
    }
}

/**
 * Boot (also before the first unlock), clock and time-zone changes, an
 * update of this app, the exact-alarm permission coming back: every
 * alarm scheduled again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = App.instance
        val why = intent.action?.substringAfterLast('.')?.lowercase() ?: "unknown"
        Scheduler.scheduleAll(ctx, why)
        Scheduler.scheduleTimer(ctx)
        Notifications.timer(ctx)
        if (app.unlocked) Sync.schedulePeriodic(ctx)
    }
}

/** A short partial wake lock, so the CPU stays up between the wake-up and the ring service. */
object Wake {
    fun hold(ctx: Context, ms: Long) {
        runCatching {
            ctx.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ccez-clock:fire")
                .acquire(ms)
        }
    }
}

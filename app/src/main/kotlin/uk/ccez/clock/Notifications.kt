package uk.ccez.clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId

object Notifications {
    const val RING_ID = 1
    private const val TIMER_ID = 2
    private const val RING = "ring"
    private const val TIMER = "timer"
    private const val SNOOZED = "snoozed"
    const val ACTION_SNOOZE_DISMISS = "uk.ccez.clock.SNOOZE_DISMISS"
    const val ACTION_TIMER_PAUSE = "uk.ccez.clock.TIMER_PAUSE"
    const val ACTION_TIMER_RESUME = "uk.ccez.clock.TIMER_RESUME"
    const val ACTION_TIMER_CANCEL = "uk.ccez.clock.TIMER_CANCEL"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun channels(ctx: Context) {
        val ring = NotificationChannel(RING, "Ringing", NotificationManager.IMPORTANCE_HIGH).apply {
            // The service plays and vibrates itself, on the alarm stream.
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }
        val timer = NotificationChannel(TIMER, "Timer", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        val snoozed = NotificationChannel(SNOOZED, "Snoozed", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        nm(ctx).createNotificationChannels(listOf(ring, timer, snoozed))
    }

    private fun flags() = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun service(ctx: Context, action: String): PendingIntent =
        PendingIntent.getService(ctx, action.hashCode(), Intent(ctx, RingService::class.java).setAction(action), flags())

    private fun broadcast(ctx: Context, action: String, id: String? = null): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(action)
        if (id != null) i.setData(Uri.parse("ccez-clock://alarm/$id")).putExtra(Scheduler.EXTRA_ID, id)
        return PendingIntent.getBroadcast(ctx, 0, i, flags())
    }

    private fun open(ctx: Context, tab: String? = null): PendingIntent = PendingIntent.getActivity(
        ctx, tab.hashCode(),
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).also { if (tab != null) it.putExtra("tab", tab) },
        flags(),
    )

    fun timeOf(ctx: Context, ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return Schedule.timeText(t.hour, t.minute, DateFormat.is24HourFormat(ctx))
    }

    /** The ringing notification: full-screen ring screen, buttons for when the phone is in use. */
    fun ringing(ctx: Context, r: Ring): Notification {
        val screen = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION), flags(),
        )
        val title = if (r.kind == "timer") "Time's up" else timeOf(ctx, r.due)
        val b = Notification.Builder(ctx, RING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(r.label.ifEmpty { if (r.kind == "timer") "Timer" else "Alarm" })
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
        if (r.kind == "timer") {
            b.addAction(Notification.Action.Builder(null, "+1 min", service(ctx, RingService.TIMER_ADD)).build())
            b.addAction(Notification.Action.Builder(null, "Stop", service(ctx, RingService.DISMISS)).build())
        } else {
            b.addAction(Notification.Action.Builder(null, "Snooze", service(ctx, RingService.SNOOZE)).build())
            b.addAction(Notification.Action.Builder(null, "Dismiss", service(ctx, RingService.DISMISS)).build())
        }
        return b.build()
    }

    /** For a fire that turned out stale: the service must still go foreground once. */
    fun quiet(ctx: Context): Notification = Notification.Builder(ctx, SNOOZED)
        .setSmallIcon(R.drawable.ic_notify).setContentTitle("Clock").build()

    fun snoozed(ctx: Context, a: Alarm, until: Long) {
        val n = Notification.Builder(ctx, SNOOZED)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Snoozed until ${timeOf(ctx, until)}")
            .setContentText(a.label.ifEmpty { "Alarm" })
            .setOngoing(true)
            .setContentIntent(open(ctx))
            .addAction(Notification.Action.Builder(null, "Dismiss", broadcast(ctx, ACTION_SNOOZE_DISMISS, a.id)).build())
            .build()
        runCatching { nm(ctx).notify(a.id, 3, n) }
    }

    fun cancelSnoozed(ctx: Context, id: String) = nm(ctx).cancel(id, 3)

    /** The timer's ongoing notification, counting down by itself (system chronometer). */
    fun timer(ctx: Context) {
        val t = App.instance.timer.state.value
        if (t.idle) {
            nm(ctx).cancel(TIMER_ID)
            return
        }
        val b = Notification.Builder(ctx, TIMER)
            .setSmallIcon(R.drawable.ic_notify)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(ctx, "timer"))
        if (t.running) {
            b.setContentTitle("Timer").setUsesChronometer(true).setChronometerCountDown(true).setWhen(t.endsAt).setShowWhen(true)
                .addAction(Notification.Action.Builder(null, "Pause", broadcast(ctx, ACTION_TIMER_PAUSE)).build())
        } else {
            b.setContentTitle("Timer paused").setContentText(Durations.timer(t.remaining))
                .addAction(Notification.Action.Builder(null, "Resume", broadcast(ctx, ACTION_TIMER_RESUME)).build())
        }
        b.addAction(Notification.Action.Builder(null, "Cancel", broadcast(ctx, ACTION_TIMER_CANCEL)).build())
        runCatching { nm(ctx).notify(TIMER_ID, b.build()) }
    }
}

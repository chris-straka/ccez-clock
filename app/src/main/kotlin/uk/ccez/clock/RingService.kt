package uk.ccez.clock

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What's ringing now (the ring screen shows it; null: nothing). */
data class Ring(
    val kind: String,
    /** The alarms ringing together (two set for the same minute ring as one). */
    val alarmIds: List<String>,
    val label: String,
    /** When it was due (ms). */
    val due: Long,
    val started: Long,
    val snooze: Int,
) {
    val alarmId: String? get() = alarmIds.firstOrNull()

    /** Another alarm due while this one rings: both ring on, both labels shown, one answer for both. */
    fun join(other: Ring): Ring {
        val added = other.alarmIds.filter { it !in alarmIds }
        if (added.isEmpty()) return this
        return copy(
            alarmIds = alarmIds + added,
            label = (label.split(" · ") + other.label).filter { it.isNotEmpty() }.distinct().joinToString(" · "),
        )
    }
}

/**
 * The ring: a foreground service that plays the sound on the alarm
 * stream with a fade-in, vibrates, shows the ring screen through a
 * full-screen notification and stops itself after `alarm.ring_minutes`.
 */
class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: Player? = null
    private var vibrator: Vibrator? = null
    private var restoreVolume: Int? = null
    private var wake: PowerManager.WakeLock? = null
    private val autoStop = Runnable { end("silenced") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = App.instance
        when (intent?.action) {
            RING_ALARM -> {
                val id = intent.getStringExtra(Scheduler.EXTRA_ID)
                val alarm = id?.let { app.alarms.get(it) }
                if (alarm == null || !alarm.enabled) {
                    app.log.log("warn", "stale-fire", "id" to id)
                    foreground(Notifications.quiet(this))
                    Scheduler.scheduleAll(this, "stale")
                    stopIfIdle()
                } else {
                    begin(Ring("alarm", listOf(alarm.id), alarm.label, intent.getLongExtra(Scheduler.EXTRA_DUE, System.currentTimeMillis()), System.currentTimeMillis(), alarm.snooze), alarm.sound.ifEmpty { app.settings.text("alarm.sound") }, alarm.vibrate)
                }
            }
            RING_TIMER -> {
                val t = app.timer.state.value
                val now = System.currentTimeMillis()
                if (!t.running || t.endsAt - now > 1500) {
                    app.log.log("warn", "stale-timer", "state" to t.state, "left" to t.left(now))
                    foreground(Notifications.quiet(this))
                    Scheduler.scheduleTimer(this)
                    stopIfIdle()
                } else {
                    val due = t.endsAt
                    app.timer.update("done") { s, n -> s.cancel(n) }
                    begin(Ring("timer", emptyList(), "", due, now, 0), app.settings.text("timer.sound"), true)
                }
            }
            DISMISS -> end("dismissed")
            SNOOZE -> end("snoozed")
            TIMER_ADD -> {
                end("plus-minute")
                app.timer.update("plus-minute") { s, n -> s.addMinute(n) }
            }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun foreground(n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(Notifications.RING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                return
            } catch (e: Exception) {
                App.instance.log.log("warn", "fgs-exempted-refused", "error" to e.toString())
            }
            startForeground(Notifications.RING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifications.RING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(Notifications.RING_ID, n)
        }
    }

    private fun begin(r: Ring, sound: String, vibrate: Boolean) {
        val app = App.instance
        val current = ringing.value
        if (current != null && current.kind == "alarm" && r.kind == "alarm") {
            // Two alarms in the same minute: one ring, both labels, one answer for both.
            val joined = current.join(r)
            ringing.value = joined
            foreground(Notifications.ringing(this, joined))
            r.alarmId?.let { app.alarms.ringing(it, retryAt(app, current.started, it)) }
            app.log.log("info", "ring-joined", "id" to r.alarmId, "with" to current.alarmIds.joinToString(), "due" to Scheduler.iso(r.due), "late_ms" to (r.started - r.due))
            Scheduler.scheduleAll(this, "rang")
            return
        }
        // A timer and an alarm: the newer rings; an alarm cut short rings again later (end()).
        if (current != null) end("replaced", keepService = true)
        foreground(Notifications.ringing(this, r))
        ringing.value = r
        // Screen off or locked: the full-screen intent opens the ring screen.
        // In use: a heads-up with the same buttons, unless it's this app on screen.
        if (app.visible) runCatching { startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        app.log.log("info", "ring", "kind" to r.kind, "id" to r.alarmId, "sound" to sound, "vibrate" to vibrate, "due" to Scheduler.iso(r.due), "late_ms" to (r.started - r.due))
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ccez-clock:ring").apply {
            acquire(app.settings.int("alarm.ring_minutes") * 60_000L + 30_000)
        }
        raiseVolume()
        // The sound must never take the ring down: our synth, else the
        // phone's own alarm tone, else silence with the screen and vibration.
        player = runCatching { Player(this).also { it.start(sound, app.settings.int("alarm.fade_seconds")) } }
            .onFailure { app.log.log("error", "sound-failed", "sound" to sound, "error" to it.toString()) }
            .getOrNull()
        if (player == null) fallbackTone()
        runCatching { if (vibrate) vibrate() }.onFailure { app.log.log("error", "vibrate-failed", "error" to it.toString()) }
        handler.postDelayed(autoStop, app.settings.int("alarm.ring_minutes") * 60_000L)
        // Killed mid-ring, it still rings again (and a one-off doesn't wait for tomorrow).
        for (id in r.alarmIds) app.alarms.ringing(id, retryAt(app, r.started, id))
        // The alarm's next ring (a repeating one's next day, or that retry) goes in now.
        Scheduler.scheduleAll(this, "rang")
    }

    private fun retryAt(app: App, started: Long, id: String): Long =
        Schedule.retryAt(started, app.settings.int("alarm.ring_minutes"), app.settings.int("alarm.ring_again_minutes"))

    private var tone: android.media.Ringtone? = null

    private fun fallbackTone() {
        runCatching {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            tone = android.media.RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
            App.instance.log.log("warn", "fallback-tone", "playing" to (tone?.isPlaying == true))
        }.onFailure { App.instance.log.log("error", "fallback-tone-failed", "error" to it.toString()) }
    }

    private fun end(how: String, keepService: Boolean = false) {
        val app = App.instance
        val r = ringing.value
        handler.removeCallbacks(autoStop)
        player?.stop(); player = null
        runCatching { tone?.stop() }; tone = null
        vibrator?.cancel(); vibrator = null
        restoreVolume()
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        if (r != null) {
            ringing.value = null
            val now = System.currentTimeMillis()
            app.log.log("info", "ring-end", "kind" to r.kind, "id" to r.alarmIds.joinToString().ifEmpty { null }, "how" to how, "rang_s" to (now - r.started) / 1000)
            for (id in r.alarmIds) {
                val until = Schedule.snoozeUntil(now, app.alarms.get(id)?.snooze ?: r.snooze)
                // Each of these reschedules, and the "Snoozed until" line follows (Scheduler).
                when (how) {
                    "snoozed" -> app.alarms.snooze(id, until)
                    "dismissed" -> app.alarms.rang(id)
                    // Unanswered (gone quiet, cut short by the timer, the service
                    // stopped): it rings again 5 minutes later (alarm.ring_again_minutes),
                    // as Samsung Clock does (owner, 2026-11-14).
                    else -> app.alarms.unanswered(id, Schedule.snoozeUntil(now, app.settings.int("alarm.ring_again_minutes")), app.settings.int("alarm.ring_again"))
                }
            }
        }
        if (!keepService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopIfIdle() {
        if (ringing.value == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun raiseVolume() {
        val pct = App.instance.settings.int("alarm.min_volume")
        if (pct <= 0) return
        runCatching {
            val am = getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val now = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val want = ceil(max * pct / 100.0).toInt().coerceIn(1, max)
            if (now < want) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0)
                restoreVolume = now
                App.instance.log.log("info", "volume-raised", "from" to now, "to" to want, "max" to max)
            }
        }.onFailure { App.instance.log.log("warn", "volume-raise-failed", "error" to it.toString()) }
    }

    private fun restoreVolume() {
        val v = restoreVolume ?: return
        restoreVolume = null
        runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
    }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 800), 1)
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        else @Suppress("DEPRECATION") v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        vibrator = v
    }

    override fun onDestroy() {
        if (ringing.value != null) end("stopped", keepService = true)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val RING_ALARM = "uk.ccez.clock.RING_ALARM"
        const val RING_TIMER = "uk.ccez.clock.RING_TIMER"
        const val DISMISS = "uk.ccez.clock.DISMISS"
        const val SNOOZE = "uk.ccez.clock.SNOOZE"
        const val TIMER_ADD = "uk.ccez.clock.TIMER_ADD"

        val ringing = MutableStateFlow<Ring?>(null)
        val now: StateFlow<Ring?> get() = ringing

        fun ringAlarm(ctx: Context, id: String, due: Long) = start(ctx, Intent(ctx, RingService::class.java).setAction(RING_ALARM).putExtra(Scheduler.EXTRA_ID, id).putExtra(Scheduler.EXTRA_DUE, due))
        fun ringTimer(ctx: Context) = start(ctx, Intent(ctx, RingService::class.java).setAction(RING_TIMER))
        fun send(ctx: Context, action: String) {
            if (ringing.value == null) return
            ctx.startService(Intent(ctx, RingService::class.java).setAction(action))
        }

        private fun start(ctx: Context, i: Intent) {
            try {
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                App.instance.log.log("error", "ring-start-failed", "error" to e.toString())
            }
        }
    }
}

/**
 * Plays one of the synthesized sounds in a loop on the alarm stream,
 * fading in from quiet over [start]'s fade seconds.
 */
class Player(private val ctx: Context) {
    private var track: AudioTrack? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false

    fun start(sound: String, fadeSeconds: Int, seconds: Int = 0) {
        val pcm = cache.getOrPut(sound) { Synth.render(sound) }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Synth.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track = t
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        val from = if (fadeSeconds > 0) 0.06f else 1f
        t.setVolume(from)
        try {
            t.play()
        } catch (e: Exception) {
            stop()
            throw e
        }
        if (fadeSeconds > 0) {
            val steps = fadeSeconds * 10
            for (i in 1..steps) handler.postDelayed({
                if (!stopped) track?.setVolume(from + (1f - from) * (i.toFloat() / steps).let { it * it })
            }, i * 100L)
        }
        if (seconds > 0) handler.postDelayed({ stop() }, seconds * 1000L)
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }

    companion object {
        private val cache = ConcurrentHashMap<String, ShortArray>()
    }
}

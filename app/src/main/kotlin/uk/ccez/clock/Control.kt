package uk.ccez.clock

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Base64
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * What `clock.ts` calls over adb: `adb shell content call --uri
 * content://uk.ccez.clock.control --method <...> [--arg ...] [--extra
 * value:s:<base64>]`. Answers "out" (or "error"), base64. Only the shell
 * user, root and this app may call it. (Agents elsewhere on the tailnet
 * use the clock server instead; this is the route for checks.)
 */
class Control : ContentProvider() {
    override fun onCreate() = true

    private fun checkCaller() {
        val uid = Binder.getCallingUid()
        if (uid != Process.SHELL_UID && uid != Process.ROOT_UID && uid != Process.myUid()) {
            throw SecurityException("only adb may control ccez-clock")
        }
    }

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor {
        checkCaller()
        val app = App.instance
        val (read, write) = android.os.ParcelFileDescriptor.createPipe()
        when (uri.path) {
            "/backup" -> {
                Thread {
                    runCatching { android.os.ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(Backup.export().toByteArray()) } }
                        .onFailure { app.log.log("error", "backup", "error" to it.toString()) }
                }.start()
                return read
            }
            "/restore" -> {
                Thread {
                    val text = runCatching { android.os.ParcelFileDescriptor.AutoCloseInputStream(read).use { it.readBytes().decodeToString() } }
                    text.mapCatching { Backup.import(it) }
                        .onSuccess { app.log.log("info", "restore", "result" to it) }
                        .onFailure { app.log.log("error", "restore", "error" to it.toString()) }
                }.start()
                return write
            }
        }
        read.close(); write.close()
        throw java.io.FileNotFoundException("no ${uri.path}")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        checkCaller()
        val app = App.instance
        val value = extras?.getString("value")?.let { String(Base64.decode(it, Base64.DEFAULT)) }
        val out = Bundle()
        try {
            val result = when (method) {
                "status" -> status()
                "schema" -> SettingDefs.schema()
                "get" -> if (arg == null) app.settings.toJson() else app.settings.getJson(arg)
                "set" -> app.settings.set(arg ?: error("set needs a setting name"), value ?: error("set needs a value")).also {
                    if (arg.startsWith("sync.")) {
                        Sync.schedulePeriodic(app)
                        Scheduler.scheduleAll(app, "setting")
                    }
                }
                "log" -> app.log.tail((arg?.toIntOrNull() ?: 50).coerceIn(1, 5000))
                "dump" -> JSONObject(status())
                    .put("settings", JSONObject(app.settings.toJson()))
                    .put("alarms", JSONArray(app.alarms.everything().map { it.toJson() }))
                    .put("timer", app.timer.state.value.toJson())
                    .put("stopwatch", app.stopwatch.state.value.toJson())
                    .toString(2)
                "list" -> JSONArray(app.alarms.alarms.value.map { withNext(it) }).toString(2)
                // value: {"time":"07:30" or "+5", "days":["mon"], "label":..., "sound":..., "vibrate":..., "snooze":..., "enabled":...}
                "add" -> {
                    val o = JSONObject(value ?: error("add needs the alarm as JSON"))
                    val a = alarmFrom(o, null)
                    withNext(app.alarms.put(a, "add")).toString(2)
                }
                "edit" -> {
                    val old = find(arg)
                    val a = alarmFrom(JSONObject(value ?: error("edit needs the changes as JSON")), old)
                    withNext(app.alarms.put(a, "edit")).toString(2)
                }
                "delete" -> find(arg).let { app.alarms.delete(it.id); "deleted ${it.id}" }
                "enable" -> withNext(app.alarms.setEnabled(find(arg).id, true)!!).toString(2)
                "disable" -> withNext(app.alarms.setEnabled(find(arg).id, false)!!).toString(2)
                "next" -> Scheduler.nextAlarmClock(app)?.let { Scheduler.iso(it) } ?: "none"
                "timer" -> timer(arg)
                // Ring now, for checks and screenshots: `ring <alarm id>` or `ring timer`.
                "ring" -> {
                    if (arg == "timer") {
                        // A timer over now: scheduling it rings it (Scheduler.scheduleTimer).
                        app.timer.update("test") { s, n -> s.start(60_000, n).copy(endsAt = n) }
                    } else {
                        RingService.ringAlarm(app, find(arg).id, System.currentTimeMillis())
                    }
                    "ringing"
                }
                "dismiss", "stop" -> { RingService.send(app, RingService.DISMISS); "ok" }
                "snooze" -> { RingService.send(app, RingService.SNOOZE); "ok" }
                "ringing" -> RingService.ringing.value?.let { JSONObject().put("kind", it.kind).put("id", it.alarmId).put("label", it.label).toString() } ?: "none"
                "sync" -> if (runBlocking { withTimeoutOrNull(25_000) { Sync.now(app, "adb") } } == true) "synced" else "sync failed (`clock.ts log`)"
                "token" -> {
                    val f = java.io.File(app.safe.filesDir, "clock/token")
                    if (value.isNullOrBlank()) f.delete() else writeSafely(f, value.trim())
                    "ok"
                }
                "update" -> { Updater.force(app); "checking" }
                else -> error("no command called $method")
            }
            // org.json writes "/" as "\/": plain slashes for people and agents.
            out.putString("out", b64(result.replace("\\/", "/")))
        } catch (e: Exception) {
            app.log.log("error", "control", "method" to method, "error" to e.toString())
            out.putString("error", b64(e.message ?: e.toString()))
        }
        return out
    }

    private fun find(arg: String?): Alarm {
        val id = arg?.trim().orEmpty()
        require(id.isNotEmpty()) { "which alarm? an id from `clock.ts list`" }
        val all = App.instance.alarms.alarms.value
        all.firstOrNull { it.id == id }?.let { return it }
        val hits = all.filter { it.id.startsWith(id) }
        return hits.singleOrNull() ?: error(if (hits.isEmpty()) "no alarm $id" else "$id matches ${hits.size} alarms")
    }

    private fun withNext(a: Alarm): JSONObject {
        val app = App.instance
        val next = Schedule.nextRing(a, app.alarms.snoozedUntil(a.id), ZonedDateTime.now())
        return a.toJson().put("next", next?.toOffsetDateTime()?.toString() ?: JSONObject.NULL)
    }

    private fun timer(arg: String?): String {
        val app = App.instance
        val words = arg?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        when (words.firstOrNull()) {
            null, "status" -> {}
            "start" -> {
                val ms = Durations.parse(words.getOrNull(1) ?: error("timer start <90|5m|1h30m>"))
                app.timer.update("start") { s, n -> s.start(ms, n) }
            }
            "pause" -> app.timer.update("pause") { s, n -> s.pause(n) }
            "resume" -> app.timer.update("resume") { s, n -> s.resume(n) }
            "cancel" -> app.timer.update("cancel") { s, n -> s.cancel(n) }
            "add" -> app.timer.update("plus-minute") { s, n -> s.addMinute(n) }
            else -> error("timer [start <duration>|pause|resume|cancel|add]")
        }
        val t = app.timer.state.value
        return t.toJson().put("left", Durations.timer(t.left(System.currentTimeMillis()))).toString(2)
    }

    private fun status(): String {
        val app = App.instance
        val next = Scheduler.nextAlarmClock(app)
        return JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("alarms", app.alarms.alarms.value.size)
            .put("on", app.alarms.alarms.value.count { it.enabled })
            .put("next", next?.let { Scheduler.iso(it) } ?: JSONObject.NULL)
            .put("ringing", RingService.ringing.value?.kind ?: JSONObject.NULL)
            .put("timer", app.timer.state.value.state)
            .put("health", Health.json(app))
            .put("sync", JSONObject().put("server", Sync.server()).put("last_ok", Sync.lastOk().takeIf { it > 0 }?.let { Scheduler.iso(it) } ?: JSONObject.NULL))
            .toString(2)
    }

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        /**
         * An alarm from agent JSON: a new one (defaults from settings) or
         * [old] with the given fields changed. "time" may be "+N" minutes.
         */
        fun alarmFrom(o: JSONObject, old: Alarm?): Alarm {
            val s = App.instance.settings
            val base = old ?: Alarm(
                id = Alarm.newId(), hour = 7, minute = 0,
                vibrate = s.bool("alarm.vibrate"), snooze = s.int("alarm.snooze_minutes"),
            )
            val merged = base.toJson()
            for (k in o.keys()) if (k !in setOf("id", "at", "deleted", "next")) merged.put(k, o.get(k))
            if (o.has("time")) {
                val (h, m) = Schedule.resolveTime(o.getString("time"), ZonedDateTime.now())
                merged.put("time", "%02d:%02d".format(h, m))
            }
            // Adding a new or edited time turns it on unless said otherwise.
            if (!o.has("enabled") && (old == null || o.has("time") || o.has("days"))) merged.put("enabled", true)
            return Alarm.parse(merged)
        }
    }
}

/** Settings and alarms as one JSON file, checked whole before anything is written. */
object Backup {
    fun export(): String {
        val app = App.instance
        return JSONObject()
            .put("ccez-clock-backup", 1)
            .put("version", BuildConfig.VERSION_NAME)
            .put("made", System.currentTimeMillis())
            .put("settings", JSONObject(app.settings.toJson()))
            .put("alarms", JSONArray(app.alarms.alarms.value.map { it.toJson() }))
            .toString(2)
    }

    fun import(json: String): String {
        val app = App.instance
        val o = JSONObject(json)
        require(o.optInt("ccez-clock-backup") == 1) { "not a ccez-clock backup" }
        val settings = o.optJSONObject("settings")?.let { SettingDefs.validated(it) }
        val alarms = o.optJSONArray("alarms")?.let { a -> (0 until a.length()).map { Alarm.parse(a.getJSONObject(it)) } }
        require(settings != null || alarms != null) { "nothing to restore" }
        require(alarms == null || alarms.map { it.id }.toSet().size == alarms.size) { "two alarms share an id" }
        settings?.let { app.settings.replaceAll(it) }
        alarms?.let { app.alarms.replaceAll(it.filter { a -> !a.deleted }) }
        return "restored settings=${settings != null} alarms=${alarms?.size ?: "-"} from ${o.optString("version")}"
    }
}

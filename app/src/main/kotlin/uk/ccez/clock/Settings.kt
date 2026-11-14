package uk.ccez.clock

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The clock's log, for agents (`clock.ts log`): one JSON line per event,
 * with context (which alarm, when it was due, when it rang, how it ended).
 */
class ClockLog(dir: File) {
    private val file = File(dir, "log.jsonl").also { it.parentFile?.mkdirs() }
    private val lock = Any()

    init {
        runCatching {
            if (file.length() > 512 * 1024) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(lines.size / 2).joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun log(level: String, event: String, vararg ctx: Pair<String, Any?>) {
        val o = JSONObject().put("t", System.currentTimeMillis()).put("level", level).put("event", event)
        ctx.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) }
        synchronized(lock) { runCatching { file.appendText(o.toString() + "\n") } }
        if (level == "error") android.util.Log.e("ccez-clock", o.toString())
    }

    fun tail(lines: Int): String {
        if (!file.exists()) return ""
        return runCatching { file.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("")
    }
}

/** Written whole or not at all. */
internal fun writeSafely(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        file.writeText(text)
        tmp.delete()
    }
}

/**
 * Every setting in one table: the settings screen, `clock.ts schema`,
 * `get` and `set` all read it.
 */
object SettingDefs {
    sealed class Kind {
        data object Bool : Kind()
        data class IntRange(val min: Int, val max: Int) : Kind()
        data object Text : Kind()
        data class Choice(val options: List<String>) : Kind()
    }

    data class Def(val key: String, val kind: Kind, val default: Any, val about: String)

    val ALL: List<Def> = listOf(
        Def("alarm.sound", Kind.Choice(Synth.SOUNDS), "chimes", "The sound for alarms that don't pick their own."),
        Def("alarm.fade_seconds", Kind.IntRange(0, 120), 30, "Seconds an alarm takes to reach full volume (0: full at once)."),
        Def("alarm.vibrate", Kind.Bool, true, "New alarms vibrate."),
        Def("alarm.snooze_minutes", Kind.IntRange(1, 60), 10, "Snooze length for new alarms."),
        Def("alarm.ring_minutes", Kind.IntRange(1, 30), 10, "An unanswered alarm goes quiet after this many minutes, then rings again a snooze later (alarm.ring_again)."),
        Def("alarm.ring_again", Kind.IntRange(0, 10), 3, "Times an unanswered alarm rings again, a snooze apart, before it counts as dismissed (0: never)."),
        Def("alarm.min_volume", Kind.IntRange(0, 100), 40, "While ringing, a lower alarm volume is raised to this percent (0: never)."),
        Def("timer.sound", Kind.Choice(Synth.SOUNDS), "marimba", "The timer's sound."),
        Def("timer.presets", Kind.Text, "1,3,5,10,15,30", "Timer presets, in minutes, comma-separated."),
        Def("sync.server", Kind.Text, BuildConfig.SYNC_SERVER, "The clock server on the tailnet. Empty: no sync."),
        Def("sync.device", Kind.Text, "", "This phone's name on the server (empty: the model)."),
        Def("sync.minutes", Kind.IntRange(0, 240), 30, "Wake to sync at least this often, even in deep sleep (0: only the 15-minute job and before each ring)."),
        Def("updates.self", Kind.Bool, true, "Install new releases from GitHub by itself."),
    )

    fun def(key: String): Def? = ALL.firstOrNull { it.key == key }

    fun checked(def: Def, v: Any?): Any = when (val k = def.kind) {
        Kind.Bool -> v as? Boolean ?: error("${def.key} takes true or false")
        is Kind.IntRange -> {
            val n = (v as? Number)?.toInt() ?: error("${def.key} takes a number from ${k.min} to ${k.max}")
            require(n in k.min..k.max) { "${def.key} takes a number from ${k.min} to ${k.max}" }
            n
        }
        Kind.Text -> (v as? String ?: error("${def.key} takes a string")).also {
            if (def.key == "timer.presets") presets(it)
        }
        is Kind.Choice -> (v as? String)?.takeIf { it in k.options } ?: error("${def.key} is one of ${k.options.joinToString()}")
    }

    /** "1,3,5" → [1, 3, 5] minutes; throws on anything else. */
    fun presets(s: String): List<Int> {
        if (s.isBlank()) return emptyList()
        val list = s.split(',').map { p -> p.trim().toIntOrNull()?.takeIf { it in 1..999 } ?: error("timer.presets is minutes like 1,3,5,10") }
        require(list.size <= 12) { "12 timer presets at most" }
        return list
    }

    fun schema(): String {
        val a = JSONArray()
        for (d in ALL) {
            val kind: Any = when (val k = d.kind) {
                Kind.Bool -> "bool"
                is Kind.IntRange -> JSONObject().put("int", JSONArray().put(k.min).put(k.max))
                Kind.Text -> "string"
                is Kind.Choice -> JSONObject().put("one_of", JSONArray(k.options))
            }
            a.put(JSONObject().put("key", d.key).put("kind", kind).put("default", d.default).put("about", d.about))
        }
        return a.toString(2)
    }

    fun validated(o: JSONObject): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (key in o.keys()) {
            val d = def(key) ?: error("no setting called $key; `clock.ts schema` lists them")
            out[key] = checked(d, o.get(key))
        }
        return out
    }
}

class ClockSettings(dir: File, private val log: ClockLog) {
    private val file = File(dir, "settings.json")
    private val values = HashMap<String, Any>()

    /** Bumped on every change. */
    val version = MutableStateFlow(0)

    init { load() }

    @Synchronized fun get(key: String): Any {
        val d = SettingDefs.def(key) ?: error("no setting called $key; `clock.ts schema` lists them")
        return values[key] ?: d.default
    }

    fun getJson(key: String): String = get(key).let { if (it is String) JSONObject.quote(it) else it.toString() }
    fun bool(key: String) = get(key) as Boolean
    fun int(key: String) = (get(key) as Number).toInt()
    fun text(key: String) = get(key) as String

    /** From JSON (`true`, `4`); a bare word is a string. Returns it as stored. */
    fun set(key: String, jsonValue: String): String {
        val d = SettingDefs.def(key) ?: error("no setting called $key; `clock.ts schema` lists them")
        val isText = d.kind is SettingDefs.Kind.Text || d.kind is SettingDefs.Kind.Choice
        val parsed: Any = when {
            // A URL or "1,3,5" isn't JSON: text settings take the words as they are, quoted or not.
            isText && !jsonValue.trimStart().startsWith("\"") -> jsonValue
            else -> runCatching { org.json.JSONTokener(jsonValue).nextValue() }.getOrDefault(jsonValue)
        }
        val v = SettingDefs.checked(d, parsed)
        synchronized(this) {
            values[key] = v
            save()
        }
        version.value += 1
        log.log("info", "setting", "key" to key, "value" to v.toString())
        return if (v is String) JSONObject.quote(v) else v.toString()
    }

    fun replaceAll(checked: Map<String, Any>) {
        synchronized(this) {
            values.clear()
            values.putAll(checked)
            save()
        }
        version.value += 1
    }

    @Synchronized fun toJson(): String {
        val o = JSONObject()
        for (d in SettingDefs.ALL) o.put(d.key, values[d.key] ?: d.default)
        return o.toString(2)
    }

    @Synchronized private fun load() {
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return log.log("warn", "settings-bad-file")
        for (d in SettingDefs.ALL) if (o.has(d.key)) runCatching { values[d.key] = SettingDefs.checked(d, o.get(d.key)) }
        // Files before format 2 held every default, not only what was set:
        // updates.self=false there was the old default, not a choice.
        if (o.optInt(FORMAT, 1) < 2 && values["updates.self"] == false) {
            values.remove("updates.self")
            log.log("info", "settings-migrated", "dropped" to "updates.self")
            save()
        }
    }

    /** Only what was set, so a changed default reaches the phone. */
    @Synchronized private fun save() {
        val o = JSONObject().put(FORMAT, 2)
        for (d in SettingDefs.ALL) values[d.key]?.let { o.put(d.key, it) }
        writeSafely(file, o.toString(2))
    }

    private companion object {
        const val FORMAT = "_format"
    }
}

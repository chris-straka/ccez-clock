package uk.ccez.clock

import java.time.DayOfWeek
import org.json.JSONArray
import org.json.JSONObject

/**
 * One alarm. `days` is a set of weekdays (empty: rings once). `at` is
 * when it last changed, anywhere (phone or an agent through the server):
 * the later change wins. A deleted alarm stays as a tombstone until the
 * server has it, so the delete travels.
 */
data class Alarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    val days: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
    val label: String = "",
    /** A sound id from [Synth.SOUNDS]; empty: the `alarm.sound` setting. */
    val sound: String = "",
    val vibrate: Boolean = true,
    /** Snooze length in minutes. */
    val snooze: Int = 10,
    val at: Long = 0,
    val deleted: Boolean = false,
) {
    init {
        require(hour in 0..23) { "hour is 0 to 23" }
        require(minute in 0..59) { "minute is 0 to 59" }
        require(snooze in 1..60) { "snooze is 1 to 60 minutes" }
        require(id.isNotBlank() && id.length <= 40 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "bad id" }
        require(label.length <= 80) { "label is 80 characters at most" }
    }

    val repeats get() = days.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("time", "%02d:%02d".format(hour, minute))
        .put("days", JSONArray(Days.order.filter { it in days }.map { Days.code(it) }))
        .put("enabled", enabled)
        .put("label", label)
        .put("sound", sound)
        .put("vibrate", vibrate)
        .put("snooze", snooze)
        .put("at", at)
        .put("deleted", deleted)

    companion object {
        /** From the JSON shape above (the server's and clock.ts's too). Throws on anything off. */
        fun parse(o: JSONObject): Alarm {
            val (h, m) = parseTime(o.getString("time"))
            val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { Days.parse(a.getString(it)) }.toSet() } ?: emptySet()
            val sound = o.optString("sound", "")
            require(sound.isEmpty() || sound in Synth.SOUNDS) { "no sound called $sound (${Synth.SOUNDS.joinToString()})" }
            return Alarm(
                id = o.getString("id"),
                hour = h,
                minute = m,
                days = days,
                enabled = o.optBoolean("enabled", true),
                label = o.optString("label", "").trim(),
                sound = sound,
                vibrate = o.optBoolean("vibrate", true),
                snooze = o.optInt("snooze", 10),
                at = o.optLong("at", 0),
                deleted = o.optBoolean("deleted", false),
            )
        }

        /** "7:30", "07:30", "19:05". */
        fun parseTime(s: String): Pair<Int, Int> {
            val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").matchEntire(s) ?: error("time is HH:MM, not \"$s\"")
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            require(h in 0..23 && min in 0..59) { "time is HH:MM, not \"$s\"" }
            return h to min
        }

        fun newId(): String {
            val r = java.security.SecureRandom()
            return (1..10).map { "0123456789abcdefghijklmnopqrstuvwxyz"[r.nextInt(36)] }.joinToString("")
        }
    }
}

object Days {
    /** Monday first, as stored; the screen orders by the locale's first day. */
    val order = DayOfWeek.entries.toList()
    val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    fun code(d: DayOfWeek) = d.name.take(3).lowercase()

    fun parse(s: String): DayOfWeek = DayOfWeek.entries.firstOrNull { code(it) == s.trim().lowercase().take(3) }
        ?: error("days are mon tue wed thu fri sat sun, not \"$s\"")
}

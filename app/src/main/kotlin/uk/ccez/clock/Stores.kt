package uk.ccez.clock

import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The alarms (files/clock/alarms.json in device-protected storage), with
 * the snoozes and which alarms the server hasn't had yet. Every change
 * goes through here and calls [onChange] (reschedule, then sync).
 */
class AlarmStore(dir: File, private val log: ClockLog) {
    private val file = File(dir, "alarms.json")
    private val all = LinkedHashMap<String, Alarm>()
    private val snoozes = HashMap<String, Long>()
    private val dirty = HashSet<String>()

    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    /** The live alarms (no tombstones), by time of day. */
    val alarms: StateFlow<List<Alarm>> = _alarms

    private val _snoozed = MutableStateFlow<Map<String, Long>>(emptyMap())
    val snoozed: StateFlow<Map<String, Long>> = _snoozed

    @Volatile var onChange: (String) -> Unit = {}

    init { load() }

    @Synchronized fun get(id: String): Alarm? = all[id]?.takeIf { !it.deleted }
    @Synchronized fun everything(): List<Alarm> = all.values.toList()
    @Synchronized fun snoozedUntil(id: String): Long? = snoozes[id]
    @Synchronized fun dirtyAlarms(): List<Alarm> = dirty.mapNotNull { all[it] }

    /** A change made here (the screen, adb): stamped now, owed to the server. */
    fun put(alarm: Alarm, why: String = "edit"): Alarm {
        val a = alarm.copy(at = System.currentTimeMillis().coerceAtLeast(alarm.at + 1))
        synchronized(this) {
            all[a.id] = a
            dirty += a.id
            if (!a.enabled || a.deleted) snoozes.remove(a.id)
            save()
        }
        log.log("info", "alarm-$why", "id" to a.id, "time" to "%02d:%02d".format(a.hour, a.minute), "days" to a.days.size, "enabled" to a.enabled)
        onChange(why)
        return a
    }

    fun delete(id: String): Boolean {
        val a = synchronized(this) { all[id]?.takeIf { !it.deleted } } ?: return false
        put(a.copy(deleted = true, enabled = false), "delete")
        return true
    }

    fun setEnabled(id: String, on: Boolean): Alarm? {
        val a = get(id) ?: return null
        if (a.enabled == on) return a
        return put(a.copy(enabled = on), if (on) "enable" else "disable")
    }

    fun snooze(id: String, until: Long) {
        synchronized(this) {
            snoozes[id] = until
            save()
        }
        onChange("snooze")
    }

    /** After a ring ends by Dismiss or silence: no snooze, and a one-off alarm turns off. */
    fun rang(id: String) {
        val a = synchronized(this) {
            snoozes.remove(id)
            save()
            all[id]
        } ?: return
        if (!a.repeats && a.enabled && !a.deleted) put(a.copy(enabled = false), "done") else onChange("done")
    }

    /**
     * Alarms from the server: each taken where it's newer than ours.
     * Returns how many changed here.
     */
    fun applyRemote(remote: List<Alarm>): Int {
        var n = 0
        synchronized(this) {
            for (r in remote) {
                val mine = all[r.id]
                if (mine == null || r.at > mine.at) {
                    all[r.id] = r
                    dirty -= r.id
                    if (!r.enabled || r.deleted) snoozes.remove(r.id)
                    n++
                }
            }
            if (n > 0) save()
        }
        if (n > 0) {
            log.log("info", "alarms-from-server", "changed" to n)
            onChange("sync")
        }
        return n
    }

    /** The server has these (as sent): no longer owed, unless changed since. */
    fun pushed(sent: List<Alarm>) {
        synchronized(this) {
            for (s in sent) if (all[s.id]?.at == s.at) dirty -= s.id
            // Tombstones the server has, a month old, can go.
            val old = System.currentTimeMillis() - 30L * 24 * 3600_000
            all.values.removeAll { it.deleted && it.id !in dirty && it.at < old }
            save()
        }
    }

    /** Backup restore: these alarms replace all (each stamped now, so they win on the server too). */
    fun replaceAll(list: List<Alarm>) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            for (a in all.values.toList()) if (a.id !in list.map { it.id }) {
                all[a.id] = a.copy(deleted = true, enabled = false, at = now)
                dirty += a.id
            }
            for (a in list) {
                all[a.id] = a.copy(at = now)
                dirty += a.id
            }
            snoozes.clear()
            save()
        }
        onChange("restore")
    }

    @Synchronized private fun publish() {
        _alarms.value = all.values.filter { !it.deleted }.sortedWith(compareBy({ it.hour }, { it.minute }, { it.label }))
        _snoozed.value = HashMap(snoozes)
    }

    @Synchronized private fun load() {
        val text = runCatching { file.readText() }.getOrNull()
        if (text != null) {
            runCatching {
                val o = JSONObject(text)
                val a = o.optJSONArray("alarms") ?: JSONArray()
                for (i in 0 until a.length()) runCatching { Alarm.parse(a.getJSONObject(i)) }.onSuccess { all[it.id] = it }
                o.optJSONObject("snoozes")?.let { s -> for (k in s.keys()) snoozes[k] = s.getLong(k) }
                o.optJSONArray("dirty")?.let { d -> for (i in 0 until d.length()) dirty += d.getString(i) }
            }.onFailure { log.log("error", "alarms-bad-file", "error" to it.toString()) }
        }
        publish()
    }

    @Synchronized private fun save() {
        val o = JSONObject()
            .put("alarms", JSONArray(all.values.map { it.toJson() }))
            .put("snoozes", JSONObject(snoozes as Map<*, *>))
            .put("dirty", JSONArray(dirty.toList()))
        writeSafely(file, o.toString(2))
        publish()
    }
}

/** The timer, with the elapsed-time clock it's scheduled against. */
class TimerStore(dir: File, private val log: ClockLog) {
    private val file = File(dir, "timer.json")
    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state

    /** Owed to the server. */
    @Volatile var dirty = false
        private set

    @Volatile var onChange: (String) -> Unit = {}

    init {
        runCatching {
            val o = JSONObject(file.readText())
            _state.value = TimerState.parse(o.getJSONObject("timer"))
            dirty = o.optBoolean("dirty")
        }
    }

    fun update(why: String, f: (TimerState, Long) -> TimerState) {
        val now = System.currentTimeMillis()
        val next = synchronized(this) {
            val n = f(_state.value, now).let { if (it.at <= _state.value.at) it.copy(at = _state.value.at + 1) else it }
            _state.value = n
            dirty = true
            save()
            n
        }
        log.log("info", "timer-$why", "state" to next.state, "left" to next.left(now))
        onChange(why)
    }

    fun applyRemote(t: TimerState): Boolean {
        synchronized(this) {
            if (t.at <= _state.value.at) return false
            _state.value = t
            dirty = false
            save()
        }
        log.log("info", "timer-from-server", "state" to t.state, "left" to t.left(System.currentTimeMillis()))
        onChange("sync")
        return true
    }

    fun pushed(sent: TimerState) = synchronized(this) {
        if (_state.value == sent) {
            dirty = false
            save()
        }
    }

    /** When it rings on the elapsed-time clock (immune to clock changes while up). */
    fun endsElapsed(): Long {
        val s = _state.value
        return SystemClock.elapsedRealtime() + (s.endsAt - System.currentTimeMillis())
    }

    private fun save() = writeSafely(file, JSONObject().put("timer", _state.value.toJson()).put("dirty", dirty).toString())
}

class StopwatchStore(dir: File) {
    private val file = File(dir, "stopwatch.json")
    private val _state = MutableStateFlow(StopwatchState())
    val state: StateFlow<StopwatchState> = _state

    init { runCatching { _state.value = StopwatchState.parse(JSONObject(file.readText())) } }

    fun update(f: (StopwatchState) -> StopwatchState) = synchronized(this) {
        _state.value = f(_state.value)
        writeSafely(file, _state.value.toJson().toString())
    }
}

package uk.ccez.clock

import org.json.JSONArray
import org.json.JSONObject

/**
 * The one timer, as timestamps: nothing ticks while the app is closed.
 * `endsAt` is wall-clock ms (the server and agents speak it); the phone
 * also keeps the elapsed-time clock it scheduled against (TimerStore).
 */
data class TimerState(
    val state: String = IDLE,
    /** The length it was set to (ms), for the progress ring. */
    val total: Long = 0,
    /** Running: when it rings (wall ms). */
    val endsAt: Long = 0,
    /** Paused: what's left (ms). */
    val remaining: Long = 0,
    /** When this state was made, anywhere: the later change wins. */
    val at: Long = 0,
) {
    val running get() = state == RUNNING
    val paused get() = state == PAUSED
    val idle get() = state == IDLE

    fun left(now: Long): Long = when (state) {
        RUNNING -> (endsAt - now).coerceAtLeast(0)
        PAUSED -> remaining
        else -> 0
    }

    fun start(ms: Long, now: Long) = TimerState(RUNNING, ms, now + ms, 0, now)
    fun pause(now: Long) = if (running) TimerState(PAUSED, total, 0, left(now), now) else this
    fun resume(now: Long) = if (paused) TimerState(RUNNING, total, now + remaining, 0, now) else this
    fun cancel(now: Long) = TimerState(IDLE, 0, 0, 0, now)
    fun addMinute(now: Long) = when (state) {
        RUNNING -> copy(total = total + 60_000, endsAt = endsAt.coerceAtLeast(now) + 60_000, at = now)
        PAUSED -> copy(total = total + 60_000, remaining = remaining + 60_000, at = now)
        else -> start(60_000, now)
    }

    fun toJson(): JSONObject = JSONObject().put("state", state).put("total", total).put("endsAt", endsAt).put("remaining", remaining).put("at", at)

    companion object {
        const val IDLE = "idle"
        const val RUNNING = "running"
        const val PAUSED = "paused"

        fun parse(o: JSONObject): TimerState {
            val s = o.optString("state", IDLE)
            require(s in listOf(IDLE, RUNNING, PAUSED)) { "timer state is idle, running or paused" }
            val t = TimerState(s, o.optLong("total"), o.optLong("endsAt"), o.optLong("remaining"), o.optLong("at"))
            require(!t.running || t.endsAt > 0) { "a running timer needs endsAt" }
            require(t.total >= 0 && t.remaining >= 0) { "negative timer" }
            return t
        }
    }
}

/**
 * The stopwatch, as timestamps. Elapsed-time clock while the phone stays
 * up; after a reboot (another boot count) the wall clock carries it.
 */
data class StopwatchState(
    val running: Boolean = false,
    val startElapsed: Long = 0,
    val startWall: Long = 0,
    val boot: Int = 0,
    /** Time banked before the current run (ms). */
    val banked: Long = 0,
    /** Total time at each lap (ms). */
    val laps: List<Long> = emptyList(),
) {
    fun elapsed(nowElapsed: Long, nowWall: Long, nowBoot: Int): Long {
        if (!running) return banked
        val run = if (nowBoot == boot && nowElapsed >= startElapsed) nowElapsed - startElapsed else (nowWall - startWall).coerceAtLeast(0)
        return banked + run
    }

    fun start(nowElapsed: Long, nowWall: Long, nowBoot: Int) =
        if (running) this else copy(running = true, startElapsed = nowElapsed, startWall = nowWall, boot = nowBoot)

    fun stop(nowElapsed: Long, nowWall: Long, nowBoot: Int) =
        if (!running) this else copy(running = false, banked = elapsed(nowElapsed, nowWall, nowBoot))

    fun lap(nowElapsed: Long, nowWall: Long, nowBoot: Int) =
        if (!running) this else copy(laps = laps + elapsed(nowElapsed, nowWall, nowBoot))

    fun reset() = StopwatchState()

    /** Each lap's own length, newest first: (number, lap ms, total ms). */
    fun lapRows(): List<Triple<Int, Long, Long>> =
        laps.mapIndexed { i, total -> Triple(i + 1, total - (if (i == 0) 0 else laps[i - 1]), total) }.reversed()

    fun toJson(): JSONObject = JSONObject().put("running", running).put("startElapsed", startElapsed).put("startWall", startWall)
        .put("boot", boot).put("banked", banked).put("laps", JSONArray(laps))

    companion object {
        fun parse(o: JSONObject): StopwatchState = StopwatchState(
            o.optBoolean("running"), o.optLong("startElapsed"), o.optLong("startWall"), o.optInt("boot"), o.optLong("banked"),
            o.optJSONArray("laps")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList(),
        )
    }
}

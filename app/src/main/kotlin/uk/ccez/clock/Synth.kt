package uk.ccez.clock

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The alarm sounds, made here from sine partials and envelopes (written
 * for this app in 2026-11: the licence is ours, and the APK carries no
 * audio). Each renders one loop, 16-bit mono, which the player repeats.
 * Pure Kotlin, deterministic: the unit tests check level and length, and
 * scripts/sounds.sh writes them out as WAV for listening.
 */
object Synth {
    const val RATE = 44_100

    val SOUNDS = listOf("chimes", "marimba", "dawn", "kalimba", "musicbox", "pulse")

    val NAMES = mapOf(
        "chimes" to "Chimes",
        "marimba" to "Marimba",
        "dawn" to "Dawn",
        "kalimba" to "Kalimba",
        "musicbox" to "Music box",
        "pulse" to "Pulse",
    )

    fun name(id: String) = NAMES[id] ?: id

    private class Buf(seconds: Double) {
        val data = DoubleArray((seconds * RATE).toInt())
        val seconds get() = data.size.toDouble() / RATE
    }

    /** A partial: frequency ratio to the note, gain, decay rate (per second). */
    private data class P(val ratio: Double, val gain: Double, val decay: Double)

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** A struck note: partials decaying on their own, a soft 4 ms attack. */
    private fun strike(b: Buf, start: Double, midi: Int, amp: Double, partials: List<P>, len: Double = 2.5) {
        val f = hz(midi)
        val s0 = (start * RATE).toInt()
        val n = (len * RATE).toInt()
        val attack = 0.004 * RATE
        for (i in 0 until n) {
            val at = s0 + i
            if (at !in b.data.indices) break
            val t = i.toDouble() / RATE
            var v = 0.0
            for (p in partials) {
                val pf = f * p.ratio
                if (pf > RATE / 2.2) continue
                v += p.gain * exp(-p.decay * t) * sin(2 * PI * pf * t)
            }
            val a = min(1.0, i / attack)
            // A short fade at the very end, so a cut note never clicks.
            val tail = min(1.0, (n - i).toDouble() / (0.02 * RATE))
            b.data[at] += amp * a * tail * v
        }
    }

    /** A held chord that swells in and out (sin² window), slightly detuned for warmth. */
    private fun swell(b: Buf, start: Double, len: Double, midis: List<Int>, amp: Double) {
        val s0 = (start * RATE).toInt()
        val n = (len * RATE).toInt()
        for (i in 0 until n) {
            val at = s0 + i
            if (at !in b.data.indices) break
            val t = i.toDouble() / RATE
            val w = sin(PI * i / n).let { it * it }
            var v = 0.0
            for (m in midis) {
                val f = hz(m)
                v += sin(2 * PI * f * 0.9985 * t) + sin(2 * PI * f * 1.0015 * t) + 0.18 * sin(2 * PI * f * 2 * t)
            }
            b.data[at] += amp * w * v / midis.size
        }
    }

    /** A soft beep: a sine with a little second harmonic, 15 ms ramps. */
    private fun beep(b: Buf, start: Double, len: Double, f: Double, amp: Double) {
        val s0 = (start * RATE).toInt()
        val n = (len * RATE).toInt()
        val ramp = 0.015 * RATE
        for (i in 0 until n) {
            val at = s0 + i
            if (at !in b.data.indices) break
            val t = i.toDouble() / RATE
            val e = min(1.0, min(i / ramp, (n - i) / ramp))
            b.data[at] += amp * e * (sin(2 * PI * f * t) + 0.12 * sin(4 * PI * f * t))
        }
    }

    private val BELL = listOf(P(1.0, 1.0, 1.1), P(2.76, 0.45, 1.9), P(5.40, 0.22, 3.2), P(8.93, 0.10, 4.5))
    private val MARIMBA = listOf(P(1.0, 1.0, 5.0), P(3.93, 0.32, 13.0), P(9.87, 0.07, 28.0))
    private val TINE = listOf(P(1.0, 1.0, 3.2), P(5.4, 0.22, 16.0), P(2.0, 0.08, 6.0))
    private val MUSICBOX = listOf(P(1.0, 1.0, 2.2), P(3.0, 0.16, 6.0), P(6.1, 0.05, 12.0))

    /** One loop of [id], peak at 0.8 of full scale. */
    fun render(id: String): ShortArray {
        val b = when (id) {
            "chimes" -> Buf(4.2).also { b ->
                // E5, G#5, B5, then E6 a little softer.
                listOf(76, 80, 83, 88).forEachIndexed { i, m -> strike(b, i * 0.42, m, if (i == 3) 0.7 else 1.0, BELL, 3.0) }
            }
            "marimba" -> Buf(3.0).also { b ->
                // A rising C major pentatonic run.
                listOf(72, 74, 76, 79, 81, 84).forEachIndexed { i, m -> strike(b, i * 0.17, m, 1.0, MARIMBA, 1.4) }
            }
            "dawn" -> Buf(5.2).also { b ->
                swell(b, 0.0, 2.8, listOf(57, 64, 69, 71, 73), 1.0) // A add9
                swell(b, 2.3, 2.8, listOf(54, 61, 64, 69, 73), 1.0) // F#m7
                strike(b, 0.6, 85, 0.25, BELL, 2.5)
                strike(b, 2.9, 81, 0.25, BELL, 2.0)
            }
            "kalimba" -> Buf(3.4).also { b ->
                listOf(67, 71, 74, 71, 79, 74, 76).forEachIndexed { i, m -> strike(b, i * 0.21, m, 1.0, TINE, 2.0) }
            }
            "musicbox" -> Buf(3.0).also { b ->
                listOf(84, 88, 91, 96, 91, 88, 86, 84).forEachIndexed { i, m -> strike(b, i * 0.16, m, 0.9, MUSICBOX, 1.8) }
            }
            "pulse" -> Buf(1.3).also { b ->
                beep(b, 0.0, 0.18, 880.0, 1.0)
                beep(b, 0.26, 0.18, 660.0, 1.0)
            }
            else -> error("no sound called $id")
        }
        return toPcm(b.data)
    }

    private fun toPcm(d: DoubleArray): ShortArray {
        var peak = 1e-9
        for (v in d) peak = max(peak, abs(v))
        val k = 0.8 / peak
        return ShortArray(d.size) { (d[it] * k * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
    }

    /** A RIFF/WAVE file of [pcm] (scripts/sounds.sh, for listening). */
    fun wav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val out = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(data)
        for (s in pcm) out.putShort(s)
        return out.array()
    }
}

package uk.ccez.clock

import java.io.File
import org.junit.Test

/** scripts/sounds.sh: writes every sound as WAV (two loops each) for listening. */
class SoundFiles {
    @Test fun write() {
        val out = System.getenv("CLOCK_SOUNDS_OUT") ?: return
        File(out).mkdirs()
        for (id in Synth.SOUNDS) {
            val one = Synth.render(id)
            File(out, "$id.wav").writeBytes(Synth.wav(one + one))
        }
    }
}

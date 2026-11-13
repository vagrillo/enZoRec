package it.agrilloit.turegistri

import it.agrilloit.turegistri.audio.NoiseReducer
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class NoiseReducerTest {

    private fun rms(x: FloatArray): Double {
        var acc = 0.0
        for (v in x) acc += v.toDouble() * v
        return kotlin.math.sqrt(acc / x.size)
    }

    @Test
    fun `riduce il rumore bianco puro`() {
        val rnd = Random(42)
        val n = NoiseReducer.FRAME * 8
        val input = FloatArray(n) { (rnd.nextFloat() - 0.5f) * 2000f } // rumore ±1000
        val out = NoiseReducer.denoiseMono(input, strength = 1.5f)
        val rmsBefore = rms(input)
        val rmsAfter = rms(out)
        assertTrue(
            "rms prima=$rmsBefore dopo=$rmsAfter",
            rmsAfter < rmsBefore * 0.6,
        )
    }

    @Test
    fun `la voce su rumore sopravvive alla riduzione`() {
        val rnd = Random(1)
        val n = NoiseReducer.FRAME * 12
        val sr = 48000
        val tone = FloatArray(n) { (sin(2 * PI * 440.0 * it / sr) * 6000).toFloat() }
        val noise = FloatArray(n) { (rnd.nextFloat() - 0.5f) * 2000f }
        val voicePlusNoise = FloatArray(n) { tone[it] + noise[it] }
        val noiseOnly = FloatArray(n) { noise[it] }

        val outVoice = NoiseReducer.denoiseMono(voicePlusNoise, strength = 1.5f)
        val outNoise = NoiseReducer.denoiseMono(noiseOnly, strength = 1.5f)

        // Il canale con la voce conserva ben più energia del solo rumore denoisato.
        assertTrue(
            "voce persa: voice=${rms(outVoice)} noise=${rms(outNoise)}",
            rms(outVoice) > rms(outNoise) * 2.0,
        )
        // E il rumore, comunque, viene ridotto rispetto all'ingresso.
        assertTrue(
            "rumore non ridotto: prima=${rms(noiseOnly)} dopo=${rms(outNoise)}",
            rms(outNoise) < rms(noiseOnly) * 0.7,
        )
    }

    @Test
    fun `silenzio resta silenzioso`() {
        val rnd = Random(3)
        val n = NoiseReducer.FRAME * 6
        val input = FloatArray(n) { (rnd.nextFloat() - 0.5f) * 60f } // quasi silenzio
        val out = NoiseReducer.denoiseMono(input, strength = 2.5f)
        assertTrue("silenzio amplificato: rms=${rms(out)}", rms(out) < 300.0)
    }

    @Test
    fun `pcm16 multicanale resta allineato`() {
        val rnd = Random(7)
        val frames = NoiseReducer.FRAME * 4
        val stereo = ShortArray(frames * 2) { (rnd.nextInt(-1000, 1000)).toShort() }
        val out = NoiseReducer.processPcm16(stereo, 48000, 2, 1.5f)
        assertTrue(out.size == stereo.size)
    }
}

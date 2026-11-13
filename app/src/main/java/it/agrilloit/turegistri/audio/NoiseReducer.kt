package it.agrilloit.turegistri.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Riduzione del rumore audio "ML-style": stima adattiva del profilo spettrale
 * del rumore (pista di decadenza minimi + VAD leggero) e gating spettrale con
 * over-subtraction configurabile. Pipeline: FFT 1024 / hop 512 / Hann / overlap-add.
 *
 * Esposto in due forme:
 *  - [Processor]: BaseAudioProcessor per il pipeline di export Media3;
 *  - [processPcm16]: funzione pura su PCM 16-bit (testabile in JVM).
 */
object NoiseReducer {

    const val FRAME = 1024
    const val HOP = 512

    /** Riduce il rumore su PCM 16-bit interleaved (chi = canali). strength ~ 0.5..3. */
    fun processPcm16(
        samples: ShortArray,
        sampleRate: Int,
        channels: Int,
        strength: Float,
    ): ShortArray {
        if (samples.isEmpty()) return samples
        val mono = FloatArray(samples.size / channels)
        var i = 0
        while (i < mono.size) {
            var acc = 0f
            for (c in 0 until channels) acc += samples[i * channels + c]
            mono[i] = acc / channels
            i++
        }
        val denoised = denoiseMono(mono, strength)
        i = 0
        while (i < mono.size) {
            for (c in 0 until channels) {
                samples[i * channels + c] = denoised[i].coerceIn(-32767f, 32767f).toInt().toShort()
            }
            i++
        }
        return samples
    }

    /** Denoise su un canale mono float. */
    fun denoiseMono(input: FloatArray, strength: Float): FloatArray {
        if (input.size < FRAME) return input
        val out = FloatArray(input.size)
        val window = hann(FRAME)
        val re = FloatArray(FRAME)
        val im = FloatArray(FRAME)
        val noiseFloor = FloatArray(FRAME / 2 + 1) { Float.MAX_VALUE }
        val minTrack = FloatArray(FRAME / 2 + 1) { Float.MAX_VALUE }
        val norm = FloatArray(input.size)
        var pos = 0
        var firstFrames = 0

        while (pos + FRAME <= input.size) {
            // Frame con finestra di Hann
            for (k in 0 until FRAME) {
                re[k] = input[pos + k] * window[k]
                im[k] = 0f
            }
            fft(re, im)

            // Magnitudini e stima del rumore:
            //  - nei primi 0.6 s il minimo tracciato è il profilo iniziale;
            //  - poi pista dei minimi con decadimento lento (il rumore è stazionario,
            //    la voce no): se il frame è "inSilenzio" (energia bassa) aggiorna.
            var energy = 0f
            for (k in 0 until FRAME) energy += re[k] * re[k] + im[k] * im[k]
            val frameRms = sqrt(energy / FRAME)
            val isSilence = frameRms < 900f // soglia leggera, in PCM full-scale ~0.027

            for (k in 0 until FRAME / 2 + 1) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                // Pista dei minimi con decadimento esponenziale
                minTrack[k] = min(minTrack[k] * 1.0006f + 0.5f, mag)
                if (isSilence || firstFrames < 20) {
                    noiseFloor[k] = if (noiseFloor[k] == Float.MAX_VALUE) mag
                    else noiseFloor[k] * 0.85f + mag * 0.15f
                }
            }
            if (firstFrames < 20) firstFrames++

            // Gating spettrale con over-subtraction e micing floor
            for (k in 0 until FRAME / 2 + 1) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                val noise = min(noiseFloor[k], minTrack[k]).coerceAtLeast(0f)
                val target = max(0f, mag - strength * noise)
                val gain = if (mag > 1f) (target / mag).coerceIn(0.08f, 1f) else 0.08f
                val g = gain * gain * (3f - 2f * gain) // smoothstep: transizioni morbide
                re[k] *= g
                im[k] *= g
                if (k in 1 until FRAME / 2) {
                    re[FRAME - k] *= g
                    im[FRAME - k] *= -g // coniugato: il gating è reale per simmetria
                }
            }
            ifft(re, im)

            // Overlap-add
            for (k in 0 until FRAME) {
                out[pos + k] += re[k] * window[k]
                norm[pos + k] += window[k] * window[k]
            }
            pos += HOP
        }

        // Copre la coda oltre l'ultimo frame completo
        while (pos < input.size) {
            out[pos] = input[pos]
            norm[pos] = 1f
            pos++
        }
        for (i in out.indices) {
            out[i] = if (norm[i] > 1e-4f) out[i] / max(norm[i], 1f) else input[i]
        }
        return out
    }

    private fun hann(n: Int): FloatArray = FloatArray(n) { k ->
        0.5f * (1f - cos(2.0 * PI * k / (n - 1))).toFloat()
    }

    /** FFT radix-2 iterativa (in-place, size potenza di 2). */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // bit reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j and bit.inv()
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var start = 0
            while (start < n) {
                var cRe = 1f
                var cIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[start + k]
                    val uIm = im[start + k]
                    val vRe = re[start + k + len / 2] * cRe - im[start + k + len / 2] * cIm
                    val vIm = re[start + k + len / 2] * cIm + im[start + k + len / 2] * cRe
                    re[start + k] = uRe + vRe
                    im[start + k] = uIm + vIm
                    re[start + k + len / 2] = uRe - vRe
                    im[start + k + len / 2] = uIm - vIm
                    val nRe = cRe * wr - cIm * wi
                    cIm = cRe * wi + cIm * wr
                    cRe = nRe
                }
                start += len
            }
            len = len shl 1
        }
    }

    /** IFFT: coniugio, FFT, scala 1/n. */
    fun ifft(re: FloatArray, im: FloatArray) {
        val n = re.size
        for (k in 0 until n) im[k] = -im[k]
        fft(re, im)
        for (k in 0 until n) {
            re[k] /= n
            im[k] = -im[k] / n
        }
    }

    /** Processore per il pipeline di export Media3 (PCM 16-bit, mono o stereo). */
    class Processor(private val strength: Float) : BaseAudioProcessor() {

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val remaining = inputBuffer.remaining()
            if (remaining == 0) {
                replaceOutputBuffer(0)
                return
            }
            val shorts = ShortArray(remaining / 2)
            inputBuffer.asShortBuffer().get(shorts)
            inputBuffer.position(inputBuffer.limit())
            NoiseReducer.processPcm16(
                shorts,
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount,
                strength,
            )
            val out = replaceOutputBuffer(shorts.size * 2)
            out.asShortBuffer().put(shorts)
        }
    }
}

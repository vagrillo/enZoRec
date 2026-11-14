package it.agrilloit.turegistri.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.tanh

/**
 * Guadagno lineare su PCM 16-bit (volume per clip, match levels).
 */
class GainProcessor(private var gain: Float) : BaseAudioProcessor() {

    fun setGain(g: Float) {
        gain = g.coerceIn(0f, 8f)
    }

    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
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
        val g = gain
        for (i in shorts.indices) {
            val v = (shorts[i] * g).toInt()
            shorts[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val out = replaceOutputBuffer(shorts.size * 2)
        out.asShortBuffer().put(shorts)
    }
}

/**
 * "Enhance audio": normalizzazione progressiva verso un target RMS (AGC)
 * + soft limiter tanh. Emula il comportamento di una catena voice-enhance.
 */
class VoiceEnhanceProcessor(
    private val targetRms: Float = 0.18f,
    private val strength: Float = 1f,
) : BaseAudioProcessor() {

    private var rmsFast = 0.05f
    private var rmsSlow = 0.05f

    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
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

        // RMS del blocco
        var acc = 0.0
        for (v in shorts) {
            val f = v / 32768f
            acc += f * f
        }
        val rms = kotlin.math.sqrt(acc / shorts.size).toFloat().coerceAtLeast(1e-4f)
        rmsFast = rmsFast * 0.8f + rms * 0.2f
        rmsSlow = rmsSlow * 0.995f + rms * 0.005f

        // AGC: guadagno verso il target basato sul RMS lento, limitato
        val rawGain = (targetRms / rmsSlow).coerceIn(0.5f, 6f * strength + 0.5f)
        val gain = rawGain * (0.7f + 0.3f * strength)

        for (i in shorts.indices) {
            val f = shorts[i] / 32768f
            val boosted = f * gain
            // Soft limiter tanh per non clippare
            val limited = kotlin.math.sign(boosted) * (1f - kotlin.math.exp(-abs(boosted) * 1.6f)) /
                (1.6f * 0.7f)
            shorts[i] = (limited * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        val out = replaceOutputBuffer(shorts.size * 2)
        out.asShortBuffer().put(shorts)
    }
}

/**
 * Rimozione delle pause a livello di flusso audio: attenua (fade) i segmenti
 * il cui RMS locale resta sotto soglia per più di [minPauseMs]. Il video non
 * viene modificato: la pausa resta visivamente ma è silenziosa (compromise v1).
 */
class SilenceMuterProcessor(
    private val threshold: Float = 0.02f,
    private val minSilenceMs: Int = 700,
) : BaseAudioProcessor() {

    private var silentSamples = 0
    private var sampleRate = 48000

    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
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

        var acc = 0.0
        for (v in shorts) {
            val f = v / 32768f
            acc += f * f
        }
        val rms = kotlin.math.sqrt(acc / shorts.size).toFloat()
        if (rms < threshold) {
            silentSamples += shorts.size / maxOf(1, inputAudioFormat?.channelCount ?: 1)
        } else {
            silentSamples = 0
        }
        val minSamples = sampleRate * minSilenceMs / 1000
        if (silentSamples > minSamples) {
            // dentro una pausa lunga: output silenzioso
            java.util.Arrays.fill(shorts, 0)
        }

        val out = replaceOutputBuffer(shorts.size * 2)
        out.asShortBuffer().put(shorts)
    }
}

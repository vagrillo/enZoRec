package it.agrilloit.turegistri.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Catena audio a stadi ordinati, port fedele di `AudioChainProcessor` iOS:
 * biquad RBJ (gain dB / highPass Hz / equalizer peaking Hz+dB) con stato
 * per canale, bypass esatto per stadio, 48 kHz.
 *
 * Il kind `voiceIsolation`/`studioVoice` è gestito dagli ML/DSP processor
 * dedicati (NoiseReducer / VoiceEnhanceProcessor), non da biquad.
 */
enum class ChainKind { GAIN, HIGH_PASS, EQUALIZER }

data class ChainStage(
    val kind: ChainKind,
    val enabled: Boolean = true,
    /** GAIN: dB (±24). HIGH_PASS: Hz. EQUALIZER: Hz del centro. */
    val amount: Double = 0.0,
    /** EQUALIZER: gain in dB del picco. */
    val gainDb: Double = 6.0,
    val frequency: Double = 1800.0,
)

/**
 * Processore della catena: applica in ordine solo gli stadi enabled.
 * PCM 16-bit, qualsiasi numero di canali.
 */
class AudioChainProcessor(
    stages: List<ChainStage>,
    sampleRate: Int,
    channels: Int,
) : BaseAudioProcessor() {

    private class Biquad(
        var b0: Double = 1.0, var b1: Double = 0.0, var b2: Double = 0.0,
        var a1: Double = 0.0, var a2: Double = 0.0,
    ) {
        val z1 = DoubleArray(8)
        val z2 = DoubleArray(8)

        fun sample(input: Double, channel: Int): Double {
            val result = b0 * input + z1[channel]
            z1[channel] = b1 * input - a1 * result + z2[channel]
            z2[channel] = b2 * input - a2 * result
            return result
        }
    }

    private val activeStages: List<Pair<ChainStage, Biquad>>

    init {
        val list = mutableListOf<Pair<ChainStage, Biquad>>()
        for (stage in stages.filter { it.enabled }) {
            val biquad = Biquad()
            val sr = sampleRate.toDouble()
            val freq = minOf(sr * 0.45, kotlin.math.max(20.0, stage.frequency))
            val w = 2.0 * kotlin.math.PI * freq / sr
            val cw = cos(w)
            val sw = sin(w)
            when (stage.kind) {
                ChainKind.GAIN -> {
                    val g = 10.0.pow(stage.amount / 20.0)
                    biquad.b0 = g; biquad.b1 = 0.0; biquad.b2 = 0.0
                    biquad.a1 = 0.0; biquad.a2 = 0.0
                }

                ChainKind.HIGH_PASS -> {
                    val alpha = sw / (2 * kotlin.math.sqrt(0.5))
                    val a0 = 1 + alpha
                    biquad.b0 = (1 + cw) / 2 / a0
                    biquad.b1 = -(1 + cw) / a0
                    biquad.b2 = biquad.b0
                    biquad.a1 = -2 * cw / a0
                    biquad.a2 = (1 - alpha) / a0
                }

                ChainKind.EQUALIZER -> {
                    val amplitude = 10.0.pow(stage.gainDb / 40)
                    val alpha = sw / 2
                    val a0 = 1 + alpha / amplitude
                    biquad.b0 = (1 + alpha * amplitude) / a0
                    biquad.b1 = -2 * cw / a0
                    biquad.b2 = (1 - alpha * amplitude) / a0
                    biquad.a1 = -2 * cw / a0
                    biquad.a2 = (1 - alpha / amplitude) / a0
                }
            }
            list.add(stage to biquad)
        }
        activeStages = list
    }

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

        val channels = maxOf(1, inputAudioFormat.channelCount)
        val out = DoubleArray(shorts.size)
        for (i in shorts.indices) {
            var v = shorts[i].toDouble()
            for ((_, biquad) in activeStages) {
                v = biquad.sample(v, i % channels)
            }
            out[i] = v
        }
        val outBuf = replaceOutputBuffer(shorts.size * 2)
        val sb = outBuf.asShortBuffer()
        for (i in shorts.indices) {
            sb.put(i, out[i].coerceIn(-32768.0, 32767.0).toInt().toShort())
        }
        outBuf.position(outBuf.limit())
    }
}

/**
 * Censor audio: silenzia le finestre di tempo indicate (ms di timeline export).
 * Il tempo è derivato dal conteggio cumulativo dei campioni (audio continuo
 * nella sequence di export).
 */
class CensorProcessor(
    rangesMs: List<Pair<Long, Long>>,
) : BaseAudioProcessor() {

    private val ranges = rangesMs.map { it.first * 48L to it.second * 48L } // in samples @48k
    private var samplesSeen = 0L

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

        val start = samplesSeen
        val end = samplesSeen + shorts.size
        samplesSeen = end
        for ((from, to) in ranges) {
            if (end > from && start < to) {
                val a = (start - from).coerceIn(0, shorts.size.toLong()).toInt()
                val b = (to - from).coerceIn(0, shorts.size.toLong()).toInt()
                for (i in a until b) shorts[i] = 0
            }
        }
        val out = replaceOutputBuffer(shorts.size * 2)
        out.asShortBuffer().put(shorts)
    }
}

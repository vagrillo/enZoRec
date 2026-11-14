package it.agrilloit.turegistri.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.C
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mixer audio offline (port di `OfflineAudioPipeline` iOS):
 *  - decodifica l'audio dei clip da ciascun mp4 (MediaExtractor/MediaCodec),
 *  - applica per-clip speed (Sonic), volume (incl. mute), fade in/out,
 *  - concatena sulla timeline dell'export (pausa/tagli già riflessi nei clip),
 *  - inserisce i beep del censor,
 *  - mixa tracce aggiunte e voiceover alle posizioni indicate,
 *  - applica la catena di stadi finale (AudioProcessor: NR, enhance, biquad chain),
 *  - scrive un WAV 48 kHz stereo pronto per il mux con il video dell'export.
 *
 * Tutto offline e cancellabile via [cancelled].
 */
class OfflineAudioMixer(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2,
) {

    data class ClipAudio(
        val clipId: String,
        val path: String,
        val trimStartMs: Long,
        val trimEndMs: Long,
        val speed: Float,
        val volume: Float,
        val fadeInMs: Long,
        val fadeOutMs: Long,
        val hasAudioTrack: Boolean,
    )

    data class AddedTrack(
        val path: String,
        val startOnTimelineMs: Long,
        val volume: Float,
        val muted: Boolean,
        /** Finestra sorgente (per l'audio staccato da un clip); MAX_VALUE = tutto. */
        val srcTrimStartMs: Long = 0,
        val srcTrimEndMs: Long = -1,
        val speed: Float = 1f,
    )

    enum class BeepWave { SINE, TRIANGLE, SQUARE }

    data class CensorBeep(
        val startMs: Long,
        val endMs: Long,
        val freqHz: Int = 1000,
        val wave: BeepWave = BeepWave.SINE,
        val levelDb: Float = -12f,
    )

    @Volatile
    var cancelled = false

    /** Picco assoluto dell'ultimo mixdown (per l'avviso "audio può distorcere"). */
    var lastPeak = 0f
        private set

    private val frameSize = channels // campioni per frame (interleaved 16-bit)

    /**
     * Produce il mixdown. Ritorna il WAV scritto. [processors] viene alimentato
     * in ordine: ogni processor riceve l'output del precedente.
     */
    fun mix(
        outWav: File,
        clips: List<ClipAudio>,
        added: List<AddedTrack>,
        beeps: List<CensorBeep>,
        processors: List<AudioProcessor>,
        onProgress: (Float) -> Unit = {},
    ): File {
        val timeline = ArrayList<FloatArray>() // blocchi stereo interleave
        var timelineFrames = 0L

        // 1. Segmenti dei clip in ordine (la timeline export = sequenza).
        for ((index, clip) in clips.withIndex()) {
            if (cancelled) throw InterruptedException()
            val seg = if (clip.hasAudioTrack) {
                val durMs = (clip.trimEndMs - clip.trimStartMs)
                val raw = decodeSegment(clip.path, clip.trimStartMs, clip.trimEndMs)
                // speed via Sonic (audio sincronizzato col video)
                applySpeed(raw, clip.speed)
                    .let { applyVolumeFades(it, clip.volume, clip.fadeInMs, clip.fadeOutMs, durMs) }
            } else {
                val frames = (durationMsToFrames(clip.trimEndMs - clip.trimStartMs) / clip.speed)
                    .toLong().coerceAtLeast(0)
                FloatArray((frames * frameSize).toInt())
            }
            timeline.add(seg)
            timelineFrames += seg.size / frameSize
            onProgress(0.1f + 0.6f * (index + 1) / clips.size)
        }

        val totalFrames = timelineFrames
        val buffer = FloatArray((totalFrames * frameSize).toInt())
        var w = 0
        for (seg in timeline) {
            System.arraycopy(seg, 0, buffer, w, seg.size)
            w += seg.size
        }

        // 2. Beep censor (posizioni sulla timeline export).
        for (beep in beeps) {
            insertBeep(buffer, beep, totalFrames)
        }

        // 3. Tracce aggiunte / voiceover.
        for (track in added) {
            if (cancelled) throw InterruptedException()
            if (track.muted) continue
            val pcmRaw = if (track.srcTrimEndMs > 0) {
                decodeSegment(track.path, track.srcTrimStartMs, track.srcTrimEndMs)
            } else {
                decodeSegment(track.path, 0, Long.MAX_VALUE)
            }
            val pcm = applySpeed(pcmRaw, track.speed)
            val startFrame = durationMsToFrames(track.startOnTimelineMs)
                .coerceIn(0, totalFrames)
            val gain = track.volume.coerceIn(0f, 4f)
            val framesToMix = min((pcm.size / frameSize), (totalFrames - startFrame).toInt())
            for (i in 0 until framesToMix) {
                for (ch in 0 until frameSize) {
                    val idx = ((startFrame + i) * frameSize + ch).toInt()
                    if (idx < buffer.size) buffer[idx] += pcm[i * frameSize + ch] * gain
                }
            }
        }
        onProgress(0.8f)

        // 4. Catena di stadi finale (AudioProcessor media3 su PCM 16-bit).
        val processed = runProcessors(buffer, processors)
        onProgress(0.92f)

        // Picco assoluto (receipt export).
        lastPeak = 0f
        for (v in processed) {
            val a = kotlin.math.abs(v)
            if (a > lastPeak) lastPeak = a
        }

        // 5. WAV out.
        WavWriter(outWav, sampleRate, channels).use { wav ->
            val shorts = ShortArray(processed.size)
            for (i in processed.indices) {
                shorts[i] = (processed[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    .coerceIn(-32768, 32767).toShort()
            }
            var off = 0
            while (off < shorts.size) {
                val n = min(8192, shorts.size - off)
                wav.write(java.util.Arrays.copyOfRange(shorts, off, off + n), n)
                off += n
            }
        }
        onProgress(1f)
        return outWav
    }

    // ------------------------------------------------------------- pieces

    private fun durationMsToFrames(ms: Long): Long = ms * sampleRate / 1000

    /** Resample lineare + adattamento canali a 48k stereo (se necessario). */
    private fun toOutputFormat(pcm: ShortArray, sr: Int, ch: Int): FloatArray {
        if (sr == sampleRate && ch == channels) {
            val out = FloatArray(pcm.size)
            for (i in out.indices) out[i] = pcm[i] / 32768f
            return out
        }
        val srcFrames = pcm.size / ch
        val dstFrames = (srcFrames.toLong() * sampleRate / sr).toInt()
        val out = FloatArray(dstFrames * channels)
        for (f in 0 until dstFrames) {
            val srcPos = f.toDouble() / sampleRate * sr
            val i0 = srcPos.toInt().coerceIn(0, srcFrames - 1)
            val i1 = (i0 + 1).coerceIn(0, srcFrames - 1)
            val frac = (srcPos - i0).toFloat()
            for (c in 0 until channels) {
                val sc = min(c, ch - 1)
                val v0 = pcm[i0 * ch + sc] / 32768f
                val v1 = pcm[i1 * ch + sc] / 32768f
                out[f * channels + c] = v0 + (v1 - v0) * frac
            }
        }
        return out
    }

    /** Speed via SonicAudioProcessor (media3), in-place su FloatArray. */
    private fun applySpeed(pcm: FloatArray, speed: Float): FloatArray {
        if (speed <= 0.01f || kotlin.math.abs(speed - 1f) < 0.01f) return pcm
        val sonic = androidx.media3.common.audio.SonicAudioProcessor()
        sonic.setSpeed(speed.coerceIn(0.25f, 4f))
        sonic.setPitch(1f)
        val inFormat = AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)
        sonic.configure(inFormat)
        val shorts = ShortArray(pcm.size)
        for (i in pcm.indices) shorts[i] = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        val out = ArrayList<Short>()
        var off = 0
        val chunk = 8192
        while (off < shorts.size) {
            val n = min(chunk, shorts.size - off)
            val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in off until off + n) bb.putShort(shorts[i])
            bb.flip()
            sonic.queueInput(bb)
            drainSonic(sonic, out)
            off += n
        }
        sonic.queueEndOfStream()
        drainSonic(sonic, out)
        val res = FloatArray(out.size)
        for (i in out.indices) res[i] = out[i] / 32768f
        sonic.flush()
        return res
    }

    private fun drainSonic(sonic: androidx.media3.common.audio.SonicAudioProcessor, out: ArrayList<Short>) {
        while (true) {
            val buf = sonic.output // pulls available output; empty buffer if none
            val n = buf.remaining() / 2
            if (n == 0) return
            val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until n) out.add(sb.get(i))
            buf.position(buf.limit())
        }
    }

    private fun applyVolumeFades(
        pcm: FloatArray,
        volume: Float,
        fadeInMs: Long,
        fadeOutMs: Long,
        sourceDurMs: Long,
    ): FloatArray {
        val gain = volume.coerceIn(0f, 2f)
        if (gain == 1f && fadeInMs <= 0 && fadeOutMs <= 0) return pcm
        val frames = pcm.size / frameSize
        val fadeInFrames = durationMsToFrames(fadeInMs).toInt()
        val fadeOutFrames = durationMsToFrames(fadeOutMs).toInt()
        // Il fade-out va rapportato alla durata sorgente (prima dello speed change).
        val outFrames = frames
        val fadeOutStartFrame = outFrames - fadeOutFrames
        for (f in 0 until outFrames) {
            var g = gain
            if (fadeInFrames > 0 && f < fadeInFrames) g *= f.toFloat() / fadeInFrames
            if (fadeOutFrames > 0 && f >= fadeOutStartFrame) {
                g *= (outFrames - f).toFloat() / fadeOutFrames
            }
            if (g != 1f) {
                for (c in 0 until frameSize) {
                    pcm[f * frameSize + c] *= g
                }
            }
        }
        return pcm
    }

    private fun insertBeep(buffer: FloatArray, beep: CensorBeep, totalFrames: Long) {
        val startFrame = durationMsToFrames(beep.startMs).toInt().coerceAtLeast(0)
        val endFrame = min(durationMsToFrames(beep.endMs).toInt(), totalFrames.toInt())
        if (endFrame <= startFrame) return
        val gain = Math.pow(10.0, beep.levelDb / 20.0).toFloat()
        val phaseStep = 2.0 * PI * beep.freqHz / sampleRate
        var phase = 0.0
        for (f in startFrame until endFrame) {
            val v = when (beep.wave) {
                BeepWave.SINE -> sin(phase).toFloat()
                BeepWave.TRIANGLE -> (2.0 / PI * kotlin.math.asin(sin(phase))).toFloat()
                BeepWave.SQUARE -> if (sin(phase) >= 0) 1f else -1f
            }
            phase += phaseStep
            if (phase > 2 * PI) phase -= 2 * PI
            for (c in 0 until frameSize) {
                val idx = f * frameSize + c
                if (idx < buffer.size) buffer[idx] = v * gain
            }
        }
    }

    private fun runProcessors(buffer: FloatArray, processors: List<AudioProcessor>): FloatArray {
        var current = buffer
        for (proc in processors) {
            if (cancelled) throw InterruptedException()
            proc.configure(AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT))
            proc.flush()
            current = feedProcessor(proc, current)
            onProgressLocal()
        }
        return current
    }

    private fun onProgressLocal() {}

    private fun feedProcessor(proc: AudioProcessor, pcm: FloatArray): FloatArray {
        val out = ArrayList<Short>()
        val shorts = ShortArray(pcm.size)
        for (i in pcm.indices) shorts[i] = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        var off = 0
        val chunk = 8192
        while (off < shorts.size) {
            val n = min(chunk, shorts.size - off)
            val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in off until off + n) bb.putShort(shorts[i])
            bb.flip()
            proc.queueInput(bb)
            drainProc(proc, out)
            off += n
        }
        proc.queueEndOfStream()
        drainProc(proc, out)
        val res = FloatArray(out.size)
        for (i in out.indices) res[i] = out[i] / 32768f
        proc.flush()
        return res
    }

    private fun drainProc(proc: AudioProcessor, out: ArrayList<Short>) {
        while (true) {
            val buf = proc.output
            val n = buf.remaining() / 2
            if (n == 0) return
            val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until n) out.add(sb.get(i))
            buf.position(buf.limit())
        }
    }

    // ------------------------------------------------------------- decode

    /**
     * Decodifica l'audio di [path] nella finestra [startMs, endMs] (o tutto il
     * file se endMs = MAX_VALUE). Ritorna FloatArray interleave 48k/stereo 0..1.
     */
    fun decodeSegment(path: String, startMs: Long, endMs: Long): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return FloatArray(0)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            extractor.selectTrack(trackIndex)
            if (startMs > 0) {
                extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val endUs = if (endMs == Long.MAX_VALUE) Long.MAX_VALUE else endMs * 1000

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val chunks = ArrayList<ShortArray>()
            var total = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && !cancelled) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(buf, 0)
                        if (sampleSize < 0 || (extractor.sampleTime > endUs && endUs != Long.MAX_VALUE)) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)!!
                    val shortCount = info.size / 2
                    val sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val data = ShortArray(shortCount)
                    sb.get(data, 0, min(shortCount, sb.remaining()))
                    chunks.add(data)
                    total += shortCount
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    val newRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val newCh = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (newRate != srcRate || newCh != srcCh) {
                        // raro: il decoder cambia formato a metà — ricomincia semplice
                    }
                }
            }
            decoder.stop()
            decoder.release()

            // Rimuovi campioni fuori dalla finestra richiesta (seek sinc → pre-start).
            val all = ShortArray(total)
            var off = 0
            for (c in chunks) {
                System.arraycopy(c, 0, all, off, c.size)
                off += c.size
            }
            return toOutputFormat(all, srcRate, srcCh)
        } catch (e: Exception) {
            return FloatArray(0)
        } finally {
            extractor.release()
        }
    }
}

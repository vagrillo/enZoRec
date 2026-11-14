package it.agrilloit.turegistri.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mux finale dell'export YouRec: copia la traccia video (già codificata) dal
 * mp4 "video-only" del Transformer e aggiunge la traccia AAC codificata dal
 * mixdown WAV del [OfflineAudioMixer]. Il video non viene ricodificato.
 *
 * Passi: (1) PCM → AAC su file temporaneo; (2) copia video + audio nel muxer
 * finale. Linearità > interleaving live: più semplice e affidabile.
 */
object AudioMuxer {

    fun mux(videoOnlyPath: String, wavPath: String, outPath: String): Boolean {
        val tmpAac = File(outPath + ".tmp_aac.m4a")
        try {
            val pcm = readWavPcm(wavPath) ?: return false
            val (samples, sampleRate, channels) = pcm
            if (!encodeAac(samples, sampleRate, channels, tmpAac.absolutePath)) return false
            return combineTracks(videoOnlyPath, tmpAac.absolutePath, outPath)
        } finally {
            tmpAac.delete()
        }
    }

    // ------------------------------------------------------------- encode

    private fun encodeAac(samples: ShortArray, sampleRate: Int, channels: Int, outPath: String): Boolean {
        val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var trackIdx = -1
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val info = MediaCodec.BufferInfo()
            var pcmOffset = 0
            var inputDone = false
            var outputDone = false
            val bytesPerFrame = channels * 2
            var started = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = encoder.getInputBuffer(inIdx)!!
                        buf.clear()
                        val maxFrames = buf.remaining() / bytesPerFrame
                        val framesLeft = (samples.size - pcmOffset) / bytesPerFrame
                        val n = minOf(maxFrames, framesLeft)
                        val ptsUs = (pcmOffset / bytesPerFrame).toLong() * 1_000_000L / sampleRate
                        if (n <= 0) {
                            encoder.queueInputBuffer(
                                inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            sb.put(samples, pcmOffset / 2, n * bytesPerFrame / 2)
                            encoder.queueInputBuffer(inIdx, 0, n * bytesPerFrame, ptsUs, 0)
                            pcmOffset += n * bytesPerFrame
                        }
                    }
                }
                var outIdx = encoder.dequeueOutputBuffer(info, 10_000)
                while (outIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIdx = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        started = true
                    } else if (outIdx >= 0) {
                        if (started && info.size > 0) {
                            val buf = encoder.getOutputBuffer(outIdx)!!
                            muxer.writeSampleData(trackIdx, buf, info)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            break
                        }
                    }
                    outIdx = encoder.dequeueOutputBuffer(info, 0)
                }
            }
            return started
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { encoder.stop(); encoder.release() }
            runCatching { muxer.stop(); muxer.release() }
        }
    }

    // ------------------------------------------------------------- combine

    private fun combineTracks(videoPath: String, audioPath: String, outPath: String): Boolean {
        val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val vExt = MediaExtractor()
        val aExt = MediaExtractor()
        try {
            vExt.setDataSource(videoPath)
            var vFormat: MediaFormat? = null
            for (i in 0 until vExt.trackCount) {
                val f = vExt.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    vFormat = f
                    vExt.selectTrack(i)
                    break
                }
            }
            aExt.setDataSource(audioPath)
            var aFormat: MediaFormat? = null
            for (i in 0 until aExt.trackCount) {
                val f = aExt.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    aFormat = f
                    aExt.selectTrack(i)
                    break
                }
            }
            if (vFormat == null || aFormat == null) return false
            val vIdx = muxer.addTrack(vFormat)
            val aIdx = muxer.addTrack(aFormat)
            muxer.start()

            copyTrack(vExt, muxer, vIdx)
            copyTrack(aExt, muxer, aIdx)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            vExt.release()
            aExt.release()
            runCatching { muxer.stop(); muxer.release() }
        }
    }

    private fun copyTrack(ext: MediaExtractor, muxer: MediaMuxer, trackIdx: Int) {
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = ext.readSampleData(buf, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = ext.sampleTime
            info.flags = ext.sampleFlags
            muxer.writeSampleData(trackIdx, buf, info)
            if (!ext.advance()) break
        }
    }

    // ---------------------------------------------------------------- wav

    /** Legge un WAV 16-bit → (ShortArray, sampleRate, channels). */
    fun readWavPcm(path: String): Triple<ShortArray, Int, Int>? {
        return try {
            val f = File(path)
            java.io.RandomAccessFile(f, "r").use { raf ->
                val header = ByteArray(44)
                raf.readFully(header)
                val channels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
                val sampleRate = readLeInt(header, 24)
                val dataIdx = findDataChunk(raf)
                if (dataIdx < 0) return null
                raf.seek(dataIdx.toLong() + 8)
                val dataLen = (f.length() - dataIdx - 8).toInt().coerceAtLeast(0)
                val bytes = ByteArray(dataLen)
                raf.readFully(bytes)
                val shorts = ShortArray(dataLen / 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                Triple(shorts, sampleRate, channels)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun findDataChunk(raf: java.io.RandomAccessFile): Int {
        raf.seek(12)
        var pos = 12
        val idBuf = ByteArray(4)
        val sizeBuf = ByteArray(4)
        while (pos + 8 < raf.length()) {
            raf.seek(pos.toLong())
            raf.readFully(idBuf)
            raf.readFully(sizeBuf)
            val size = readLeInt(sizeBuf, 0)
            if (String(idBuf, Charsets.US_ASCII) == "data") return pos
            pos += 8 + size + (size % 2)
        }
        return -1
    }

    private fun readLeInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}

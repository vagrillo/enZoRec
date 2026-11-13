package it.agrilloit.turegistri.record

import android.media.MediaRecorder
import android.util.Size
import android.view.Surface
import java.io.File

/**
 * Recorder video (e opzionalmente audio) alimentato via Surface:
 *  - per le singole camere: la surface è quella di MediaRecorder (recorder.getSurface()),
 *    disponibile dopo prepare(), e va aggiunta alla sessione camera;
 *  - per il composito: si passa una surface di input persistente (alimentata dal GL).
 *
 * Le singole camere NON registrano audio: l'audio viene catturato separatamente
 * in WAV per la postproduzione. Solo il composito (pronto da condividere) include
 * l'audio del microfono predefinito.
 */
class VideoRecorder(
    val file: File,
    private val videoSize: Size,
    private val fps: Int,
    private val bitrate: Int,
    private val orientationHint: Int,
    private val externalInput: Surface? = null,
    private val withAudio: Boolean,
    private val audioSource: Int = MediaRecorder.AudioSource.MIC,
) {

    private var recorder: MediaRecorder? = null
    private var prepared = false
    private var ownSurface: Surface? = null

    /**
     * Surface su cui arrivano i frame: quella persistente passata dall'esterno
     * (composito) oppure quella creata da MediaRecorder dopo prepare (camera).
     */
    val inputSurface: Surface?
        get() = externalInput ?: ownSurface

    @Suppress("DEPRECATION")
    fun prepare() {
        val r = MediaRecorder()
        try {
            // 1) Sorgenti (prima di tutto: portano lo stato da IDLE a INITIALIZED).
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (withAudio) {
                r.setAudioSource(audioSource)
            }
            // 2) Formato di uscita e file.
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(file.absolutePath)
            // 3) Encoder e parametri.
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(videoSize.width, videoSize.height)
            r.setVideoFrameRate(fps)
            r.setVideoEncodingBitRate(bitrate)
            if (withAudio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(128_000)
                r.setAudioSamplingRate(48_000)
                r.setAudioChannels(1)
            }
            if (orientationHint >= 0) {
                r.setOrientationHint(orientationHint)
            }
            // 4) Surface persistente esterna (solo composito) e prepare.
            if (externalInput != null) {
                r.setInputSurface(externalInput)
            }
            r.prepare()
            recorder = r
            prepared = true
            ownSurface = r.surface
        } catch (e: Exception) {
            runCatching { r.release() }
            throw e
        }
    }

    /** @return elapsedRealtimeNanos all'istante dello start. */
    fun start(): Long {
        val r = recorder ?: error("Recorder non preparato")
        r.start()
        return android.os.SystemClock.elapsedRealtimeNanos()
    }

    /** @return true se il file è stato finalizzato correttamente. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        return try {
            r.stop()
            true
        } catch (e: Exception) {
            // Nessun frame valido (es. camera fermata troppo presto): file da buttare.
            file.delete()
            false
        } finally {
            runCatching { r.release() }
            recorder = null
            prepared = false
        }
    }

    /** Pausa la registrazione (frame timestamp congelati). API 24+. */
    fun pause() {
        runCatching { recorder?.pause() }
    }

    fun resume() {
        runCatching { recorder?.resume() }
    }

    fun release() {
        val r = recorder ?: return
        runCatching {
            if (prepared) r.stop()
        }
        runCatching { r.release() }
        recorder = null
        prepared = false
        ownSurface = null
    }
}

package it.agrilloit.turegistri.record

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Display
import it.agrilloit.turegistri.audio.WavWriter
import java.io.File

/**
 * Modalità "Screen display": cattura simultanea di
 *  1. lo SCHERMO (MediaProjection + VirtualDisplay + MediaRecorder → schermo.mp4);
 *  2. l'AUDIO DELLE ALTRE APP (il video in split screen, AudioPlaybackCapture
 *     → app_audio.wav, 48 kHz mono).
 * La camera e i microfoni sono gestiti dal RecordingEngine: qui solo schermo + app audio.
 */
class ScreenStreams private constructor(
    private val context: Context,
    private val projection: MediaProjection,
    private val outputDir: File,
    private val screenSize: Size,
    private val onError: (String) -> Unit,
) {

    companion object {
        /**
         * Crea i flussi da una MediaProjection già ottenuta (il consenso è stato
         * dato all'ingresso in modalità). La projection NON viene fermata qui:
         * resta valida per i take successivi della sessione.
         */
        fun create(
            context: Context,
            projection: MediaProjection,
            outputDir: File,
            onError: (String) -> Unit,
        ): ScreenStreams? {
            return try {
                val metrics = context.resources.displayMetrics
                ScreenStreams(
                    context,
                    projection,
                    outputDir,
                    Size(metrics.widthPixels, metrics.heightPixels),
                    onError,
                )
            } catch (e: Exception) {
                onError("MediaProjection: ${e.message}")
                null
            }
        }
    }

    private val thread = HandlerThread("YouRecScreen").apply { start() }
    private val handler = Handler(thread.looper)

    private var recorder: MediaRecorder? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    private var playbackRecord: AudioRecord? = null
    private var playbackWav: WavWriter? = null
    private var playbackThread: Thread? = null
    private val playbackRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    var screenFile: File? = null
        private set
    var appAudioFile: File? = null
        private set
    var startedAtElapsedNanos: Long = 0
        private set

    private val projectionListener = object : MediaProjection.Callback() { }

    /** Avvia schermo + audio app. Ritorna false se qualcosa non è partito. */
    fun start(): Boolean {
        var okScreen = false
        var okAudio = false
        try {
            // ---------- 1. Schermo ----------
            val r = MediaRecorder()
            val outFile = File(outputDir, "schermo.mp4")
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(outFile.absolutePath)
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(screenSize.width, screenSize.height)
            r.setVideoFrameRate(30)
            r.setVideoEncodingBitRate(10_000_000)
            r.prepare()
            val display = projection.createVirtualDisplay(
                "YouRecScreen",
                screenSize.width, screenSize.height,
                context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler,
            )
            r.start()
            recorder = r
            virtualDisplay = display
            screenFile = outFile
            okScreen = true

            // ---------- 2. Audio delle altre app ----------
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val record = AudioRecord.Builder()
                .setContext(context)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            val wav = WavWriter(File(outputDir, "app_audio.wav"), 48000, 1)
            playbackRecord = record
            playbackWav = wav
            playbackRunning.set(true)
            playbackThread = Thread({
                record.startRecording()
                val buf = ShortArray(4096)
                while (playbackRunning.get()) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) runCatching { wav.write(buf, n) } else break
                }
            }, "YouRecAppAudio")
            playbackThread?.start()
            okAudio = true

            startedAtElapsedNanos = android.os.SystemClock.elapsedRealtimeNanos()
            projection.registerCallback(projectionListener, handler)
        } catch (e: Exception) {
            onError("Screen display: ${e.message}")
        }
        if (!okScreen || !okAudio) {
            stop()
            return false
        }
        return true
    }

    /** Ferma e finalizza i file. */
    fun stop() {
        runCatching {
            recorder?.stop()
        }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        playbackRunning.set(false)
        playbackThread?.join(2000)
        playbackThread = null
        runCatching { playbackRecord?.stop() }
        runCatching { playbackRecord?.release() }
        playbackRecord = null
        runCatching { playbackWav?.close() }
        playbackWav = null
        // La projection resta attiva: riutilizzabile per il prossimo take.
    }
}

package it.agrilloit.turegistri.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import kotlin.math.sqrt

/**
 * Registra tutti i microfoni secondo il piano di [MicEnumerator], uno WAV per
 * "traccia": multicanale => 1 WAV multicanale, per-mic => 1 WAV mono per microfono,
 * single => 1 WAV mono. Ogni traccia legge su un thread dedicato.
 * Supporta pausa/ripresa (gap nel WAV) ed espone il livello RMS live per la UI.
 */
class MicRecorder(
    private val plan: MicEnumerator.Plan,
    private val outputDir: File,
    private val onError: (String) -> Unit,
    private val useNoiseSuppressor: Boolean = true,
) {

    /** Livello RMS 0..1 della prima traccia, aggiornato ~10 volte al secondo. */
    val level = java.util.concurrent.atomic.AtomicReference(0f)
    private val paused = java.util.concurrent.atomic.AtomicBoolean(false)

    data class TrackInfo(
        val file: File,
        val channels: Int,
        val micDescription: String,
    )

    private class RunningTrack(
        val record: AudioRecord,
        val wav: WavWriter,
        val thread: Thread,
        val info: TrackInfo,
    )

    private val tracks = ArrayList<RunningTrack>()
    private val suppressors = ArrayList<android.media.audiofx.NoiseSuppressor>()
    private val recordingFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Timestamps di inizio cattura. */
    var startedAtElapsedNanos: Long = 0
        private set
    var startedAtEpochMs: Long = 0
        private set

    val trackInfos: List<TrackInfo>
        get() = synchronized(tracks) { tracks.map { it.info } }

    val label: String get() = plan.label

    /** Avvia tutte le tracce. Ritorna false se nessuna traccia è partita. */
    fun start(): Boolean {
        val started = ArrayList<RunningTrack>()
        when (plan) {
            is MicEnumerator.Plan.Multichannel -> {
                val record = MicEnumerator.buildRecord(
                    source = MediaRecorder.AudioSource.MIC,
                    sampleRate = MicEnumerator.SAMPLE_RATE,
                    channelConfig = AudioFormat.CHANNEL_IN_FRONT,
                    indexMask = plan.channelIndexMask,
                )
                if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                    val file = File(outputDir, "mic_multicanale.wav")
                    startTrack(started, record, file, plan.channels, "mic multicanale")
                } else {
                    record?.release()
                }
            }

            is MicEnumerator.Plan.PerMic -> {
                plan.devices.forEachIndexed { i, device ->
                    val record = MicEnumerator.buildRecord(
                        source = MediaRecorder.AudioSource.MIC,
                        sampleRate = MicEnumerator.SAMPLE_RATE,
                        channelConfig = AudioFormat.CHANNEL_IN_MONO,
                        preferredDevice = device,
                    )
                    if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                        val name = describeMic(device, i)
                        startTrack(started, record, File(outputDir, "mic_$i.wav"), 1, name)
                    } else {
                        record?.release()
                    }
                }
            }

            is MicEnumerator.Plan.Single -> {
                val record = MicEnumerator.buildRecord(
                    source = plan.source,
                    sampleRate = MicEnumerator.SAMPLE_RATE,
                    channelConfig = AudioFormat.CHANNEL_IN_MONO,
                )
                if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                    startTrack(started, record, File(outputDir, "mic_0.wav"), 1, "microfono predefinito")
                } else {
                    record?.release()
                }
            }
        }
        if (started.isEmpty()) return false
        synchronized(tracks) { tracks.addAll(started) }
        startedAtElapsedNanos = SystemClock.elapsedRealtimeNanos()
        startedAtEpochMs = System.currentTimeMillis()
        return true
    }

    /** Ferma tutte le tracce e finalizza i WAV. Ritorna le info delle tracce scritte. */
    fun stop(): List<TrackInfo> {
        if (!recordingFlag.compareAndSet(true, false)) {
            return synchronized(tracks) { tracks.map { it.info } }
        }
        synchronized(tracks) { tracks.toList() }.forEach { track ->
            try {
                track.thread.join(2000)
            } catch (_: InterruptedException) {
            }
            runCatching { track.record.stop() }
            runCatching { track.record.release() }
            runCatching { track.wav.close() }
        }
        synchronized(tracks) {
            val infos = tracks.map { it.info }
            tracks.clear()
            return infos
        }
    }

    /** Pausa la scrittura dei campioni (gap nei WAV) e azzera il livello. */
    fun pause() {
        paused.set(true)
        level.set(0f)
    }

    fun resume() {
        paused.set(false)
    }

    private fun startTrack(
        out: MutableList<RunningTrack>,
        record: AudioRecord,
        file: File,
        channels: Int,
        description: String,
    ) {
        recordingFlag.set(true)
        if (useNoiseSuppressor && android.media.audiofx.NoiseSuppressor.isAvailable()) {
            runCatching {
                val ns = android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)
                ns.enabled = true
                suppressors.add(ns)
            }
        }
        val channelsFinal = record.channelCount.takeIf { it > 0 } ?: channels
        val wav = try {
            WavWriter(file, MicEnumerator.SAMPLE_RATE, channelsFinal)
        } catch (e: Exception) {
            record.release()
            onError("Impossibile scrivere ${file.name}: ${e.message}")
            return
        }
        val buffer = ShortArray(4096 * channelsFinal)
        val thread = Thread({
            record.startRecording()
            val flag = recordingFlag
            while (flag.get()) {
                if (paused.get()) {
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                    }
                    continue
                }
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0) {
                    // RMS della prima traccia come indicatore di livello.
                    var acc = 0.0
                    var i = 0
                    while (i < n) {
                        val s = buffer[i].toDouble()
                        acc += s * s
                        i += channelsFinal
                    }
                    val rms = sqrt(acc / (n / channelsFinal)) / 32768.0
                    level.set(rms.toFloat().coerceIn(0f, 1f))
                    try {
                        wav.write(buffer, n)
                    } catch (e: Exception) {
                        onError("Errore scrittura audio: ${e.message}")
                        break
                    }
                } else if (n < 0) {
                    break
                }
            }
        }, "TuRegistri-mic")
        thread.start()
        val track = RunningTrack(
            record, wav, thread,
            TrackInfo(file, channelsFinal, description),
        )
        out.add(track)
    }

    private fun describeMic(device: android.media.AudioDeviceInfo, index: Int): String {
        val address = device.address?.takeIf { it.isNotBlank() } ?: "indice $index"
        return "mic integrato ($address)"
    }
}

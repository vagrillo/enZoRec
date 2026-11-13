package it.agrilloit.turegistri.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.room.withTransaction
import it.agrilloit.turegistri.record.RecordingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Repository YouRec: progetti + take, miniature e aggancio delle sessioni
 * di registrazione (che vivono nel motore TuRegistri riutilizzato).
 */
class ProjectsRepository(private val context: Context) {

    private val db = YouRecDatabase.get(context)
    val projects: Flow<List<ProjectEntity>> = db.projectDao().observeProjects()

    suspend fun get(id: String): ProjectEntity? = db.projectDao().get(id)

    /** Id del progetto più recente (per il tab Edit). */
    suspend fun latestProjectId(): String? = db.projectDao().mostRecent()?.id
    suspend fun takeCounts(): Map<String, Int> =
        db.takeDao().counts().associate { it.projectId to it.cnt }

    /** "Continue recording": il prossimo take finisce in questo progetto. */
    suspend fun touch(projectId: String) {
        db.projectDao().get(projectId)?.let { update(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    /** Import di un video esterno in un nuovo progetto. */
    suspend fun importVideo(copiedFile: File, displayName: String?): ProjectEntity {
        val now = System.currentTimeMillis()
        val project = createProject(displayName ?: "Imported " + SimpleDateFormat("dd MMM - HH:mm", Locale.US).format(Date(now)))
        val take = TakeEntity(
            id = UUID.randomUUID().toString(),
            projectId = project.id,
            dirPath = copiedFile.parent ?: "",
            startedAt = now,
            compositePath = copiedFile.absolutePath,
            hasAudio = true,
        )
        db.takeDao().insert(take)
        extractThumbnail(take)?.let {
            db.projectDao().update(project.copy(thumbnailPath = it, updatedAt = now))
        }
        return project
    }
    suspend fun takes(projectId: String): List<TakeEntity> = db.takeDao().forProject(projectId)
    fun takesFlow(projectId: String): Flow<List<TakeEntity>> = db.takeDao().observeForProject(projectId)

    suspend fun createProject(name: String? = null): ProjectEntity {
        val now = System.currentTimeMillis()
        val name2 = name ?: "Project " + SimpleDateFormat("dd MMM - HH:mm", Locale.US).format(Date(now))
        val project = ProjectEntity(
            id = UUID.randomUUID().toString(),
            name = name2,
            createdAt = now,
            updatedAt = now,
        )
        db.projectDao().insert(project)
        return project
    }

    suspend fun update(project: ProjectEntity) =
        db.projectDao().update(project.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(projectId: String) = db.withTransaction {
        takes(projectId).forEach { take ->
            runCatching { File(take.dirPath).deleteRecursively() }
            db.takeDao().delete(take.id)
        }
        db.projectDao().delete(projectId)
    }

    suspend fun rename(projectId: String, name: String) {
        db.projectDao().get(projectId)?.let { update(it.copy(name = name)) }
    }

    /**
     * Aggancia una sessione di registrazione appena conclusa al progetto corrente
     * (il più recente, oppure ne crea uno nuovo con la data odierna).
     */
    suspend fun onTakeSaved(result: RecordingEngine.SessionResult): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var project = db.projectDao().mostRecent()
        if (project == null) {
            project = createProject(null)
        }
        val composite = result.files.firstOrNull { it.name == "composito.mp4" }
        val cam0 = result.files.firstOrNull { it.name == "cam0.mp4" }
        val take = TakeEntity(
            id = UUID.randomUUID().toString(),
            projectId = project.id,
            dirPath = result.dir.absolutePath,
            startedAt = now,
            compositePath = (composite ?: cam0)?.absolutePath,
            hasAudio = composite != null,
            markersJson = result.markers.joinToString(","),
        )
        db.takeDao().insert(take)
        if (project.thumbnailPath == null) {
            extractThumbnail(take)?.let { path ->
                db.projectDao().update(project.copy(thumbnailPath = path, updatedAt = now))
                return@withContext project.id
            }
        }
        db.projectDao().update(project.copy(updatedAt = now))
        project.id
    }

    suspend fun setExported(projectId: String, exportPath: String) {
        db.projectDao().get(projectId)?.let {
            update(it.copy(exported = true, lastExportPath = exportPath))
        }
    }

    /** Estrae un frame ridotto dal video del take e lo salva nella sessione. */
    suspend fun extractThumbnail(take: TakeEntity): String? = withContext(Dispatchers.IO) {
        val video = take.compositePath ?: return@withContext null
        runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(video)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            frame ?: return@runCatching null
            val scaled = Bitmap.createScaledBitmap(
                frame,
                240,
                (240f * frame.height / frame.width).toInt().coerceAtLeast(1),
                true,
            )
            val out = File(File(take.dirPath), "thumb.jpg")
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            out.absolutePath
        }.getOrNull()
    }

    /** Durata (ms) di un video, 0 se non leggibile. */
    fun videoDurationMs(path: String): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(path)
        val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        retriever.release()
        d
    }.getOrDefault(0L)

    /** Estrae le miniature di un video per la timeline (una ogni [strideMs]). */
    fun timelineThumbnails(path: String, strideMs: Long = 1000, maxCount: Int = 60): List<Bitmap> =
        runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong() ?: 0L
            val frames = ArrayList<Bitmap>()
            var t = 0L
            while (t < durationMs && frames.size < maxCount) {
                retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { frames.add(Bitmap.createScaledBitmap(it, 96, 54, true)) }
                t += strideMs
            }
            retriever.release()
            frames
        }.getOrDefault(emptyList())

    /** Durata (ms) di un WAV PCM16 dal file (dati/2/canali? mono: solo data bytes). */
    fun wavDurationMs(wavPath: String): Long = runCatching {
        val file = File(wavPath)
        val dataBytes = (file.length() - 44).coerceAtLeast(0)
        // mic_0.wav è mono 16-bit a 48 kHz nella nostra pipeline.
        dataBytes / 2 / 48000 * 1000L
    }.getOrDefault(0L)

    /** Picchi (0..1) della waveform da un WAV, calcolo streaming (niente caricamento integrale). */
    fun waveformPeaks(wavPath: String, bucketCount: Int = 600): List<Float> = runCatching {
        val file = File(wavPath)
        if (!file.exists()) return@runCatching emptyList()
        val dataBytes = (file.length() - 44).coerceAtLeast(0)
        val samples = (dataBytes / 2).toInt()
        if (samples <= 0) return@runCatching emptyList()
        val perBucket = (samples / bucketCount).coerceAtLeast(1)
        val peaks = FloatArray(bucketCount)
        file.inputStream().use { input ->
            val header = ByteArray(44)
            var read = 0
            while (read < 44) {
                val r = input.read(header, read, 44 - read)
                if (r < 0) return@runCatching emptyList()
                read += r
            }
            var sampleIdx = 0
            var bucket = 0
            var peak = 0
            val buf = ByteArray(4096)
            var bufPos = 0
            var bufLen = 0
            while (bucket < bucketCount) {
                if (bufPos + 1 >= bufLen) {
                    bufLen = input.read(buf)
                    bufPos = 0
                    if (bufLen < 0) break
                    continue
                }
                val v = ((buf[bufPos].toInt() and 0xFF) or ((buf[bufPos + 1].toInt() and 0xFF) shl 8)).toShort()
                bufPos += 2
                val abs = if (v < 0) -v.toInt() else v.toInt()
                if (abs > peak) peak = abs
                sampleIdx++
                if (sampleIdx >= (bucket + 1) * perBucket) {
                    peaks[bucket] = (peak / 32768f).coerceIn(0f, 1f)
                    peak = 0
                    bucket++
                }
            }
        }
        peaks.toList()
    }.getOrDefault(emptyList())

    companion object {
        /** Cartella radice delle sessioni (condivisa col motore). */
        fun sessionsRoot(context: Context): File {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, "sessioni")
        }
    }
}

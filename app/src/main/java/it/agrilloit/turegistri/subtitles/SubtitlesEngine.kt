package it.agrilloit.turegistri.subtitles

import it.agrilloit.turegistri.model.TitleDesign

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Motore sottotitoli (port di `Subtitles.swift` iOS): ASR on-device con
 * **vosk** (equivalente funzionale di QwenASR — completamente offline, modelli
 * piccoli scaricati una volta), raggruppamento delle parole in frasi 1–16
 * parole sui gap > 320 ms e punteggiatura finale, cue editabili, burn-in e
 * export SRT.
 *
 * I tempi sono stime basate sull'output di vosk (che fornisce start/end per
 * parola), come nel port iOS.
 */
object SubtitlesEngine {

    /** Modelli piccoli supportati (download ~40 MB, giro unico). */
    data class LangModel(val lang: String, val label: String, val zipUrl: String, val dirName: String)

    val LANGUAGES = listOf(
        LangModel(
            "it", "Italiano",
            "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip",
            "vosk-model-small-it-0.22",
        ),
        LangModel(
            "en", "English (US)",
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            "vosk-model-small-en-us-0.15",
        ),
    )

    fun modelsDir(context: Context): File = File(context.filesDir, "vosk").apply { mkdirs() }

    fun downloadedLangs(context: Context): List<String> =
        LANGUAGES.filter { File(modelsDir(context), it.dirName).isDirectory }.map { it.lang }

    /** Scarica e spacchetta il modello (chiamare su IO). */
    fun downloadModel(context: Context, lang: LangModel, onProgress: (Float) -> Unit): Boolean {
        val dir = modelsDir(context)
        val zipFile = File(dir, "${lang.dirName}.zip")
        val target = File(dir, lang.dirName)
        if (target.isDirectory && File(target, "am").isDirectory) return true
        val conn = java.net.URL(lang.zipUrl).openConnection()
        conn.connect()
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: 48_000_000L
        conn.getInputStream().use { input ->
            java.io.FileOutputStream(zipFile).use { out ->
                val buf = ByteArray(1 shl 16)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    read += n
                    onProgress((read.toFloat() / total).coerceIn(0f, 1f) * 0.9f)
                }
            }
        }
        onProgress(0.92f)
        // unzip
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            val buf = ByteArray(1 shl 16)
            while (entry != null) {
                // i zip di vosk hanno una cartella root: riscrivi dentro dir/
                val name = entry.name.removePrefix(lang.dirName).trimStart('/')
                if (name.isNotEmpty()) {
                    val out = File(dir, if (entry.isDirectory) "$name/" else name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        java.io.FileOutputStream(out).use { out2 ->
                            var n = zis.read(buf)
                            while (n > 0) {
                                out2.write(buf, 0, n)
                                n = zis.read(buf)
                            }
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }
        zipFile.delete()
        val ok = File(target, "am").isDirectory
        onProgress(1f)
        return ok
    }

    fun deleteModel(context: Context, lang: LangModel) {
        File(modelsDir(context), lang.dirName).deleteRecursively()
    }

    // ------------------------------------------------------------ ASR

    data class Word(val word: String, val startMs: Long, val endMs: Long)

    /** Trascrive PCM 16 kHz mono (16-bit LE) e ritorna le parole con timing. */
    fun transcribePcm(context: Context, lang: String, pcm16k: ShortArray, onChunk: (Float) -> Unit = {}): List<Word> {
        val model = LANGUAGES.firstOrNull { it.lang == lang }
            ?: return emptyList()
        val modelPath = File(modelsDir(context), model.dirName).absolutePath
        val voskModel = Model(modelPath)
        val words = ArrayList<Word>()
        try {
            val recognizer = Recognizer(voskModel, 16000f, "[\"[unk]\"]")
            val stream = ByteArrayInputStream(toBytes(pcm16k))
            val buf = ByteArray(4096)
            var fed = 0L
            val total = (pcm16k.size * 2).toLong()
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                if (recognizer.acceptWaveForm(buf, n)) {
                    parseWords(recognizer.result, words)
                }
                fed += n
                onChunk((fed.toFloat() / total).coerceIn(0f, 1f))
            }
            parseWords(recognizer.finalResult, words)
            recognizer.close()
        } finally {
            voskModel.close()
        }
        return words
    }

    private fun parseWords(json: String, out: ArrayList<Word>) {
        if (json.isNullOrEmpty()) return
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        val result = obj.optJSONArray("result") ?: return
        for (i in 0 until result.length()) {
            val w = result.getJSONObject(i)
            out.add(
                Word(
                    word = w.optString("word", ""),
                    startMs = (w.optDouble("start", 0.0) * 1000).toLong(),
                    endMs = (w.optDouble("end", 0.0) * 1000).toLong(),
                )
            )
        }
    }

    private fun toBytes(pcm: ShortArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) bb.putShort(s)
        return bb.array()
    }

    // ------------------------------------------------------- raggruppamento

    /**
     * Raggruppa le parole in cue (1–16 parole; break su gap > 320 ms e
     * punteggiatura finale, port della phrase grouping iOS).
     */
    fun groupCues(words: List<Word>, timelineOffsetMs: Long = 0): List<Cue> {
        val cues = ArrayList<Cue>()
        var current = ArrayList<Word>()
        for (w in words) {
            if (w.word == "[unk]") continue
            val gap = if (current.isEmpty()) 0L else w.startMs - current.last().endMs
            val endsSentence = current.isNotEmpty() &&
                Regex("[.!?…]$").containsMatchIn(current.last().word)
            val mustBreak = endsSentence || gap > 320 || current.size >= 16
            if (mustBreak && current.isNotEmpty()) {
                cues.add(cueOf(current, timelineOffsetMs))
                current = ArrayList()
            }
            current.add(w)
        }
        if (current.isNotEmpty()) cues.add(cueOf(current, timelineOffsetMs))
        return cues
    }

    private fun cueOf(words: List<Word>, offset: Long): Cue =
        Cue(
            id = java.util.UUID.randomUUID().toString(),
            startMs = words.first().startMs + offset,
            endMs = words.last().endMs + offset,
            text = words.joinToString(" ") { it.word }.replace(" ,", ",").replace(" .", ".")
                .replace(" !", "!").replace(" ?", "?"),
        )

    // ------------------------------------------------------------ SRT

    fun toSrt(cues: List<Cue>): String {
        val sb = StringBuilder()
        cues.forEachIndexed { i, c ->
            sb.append(i + 1).append("\n")
            sb.append(srtTime(c.startMs)).append(" --> ").append(srtTime(c.endMs)).append("\n")
            sb.append(c.text).append("\n\n")
        }
        return sb.toString()
    }

    private fun srtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val mmm = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, mmm)
    }
}

/** Una riga di sottotitolo sulla timeline export (editabile). */
data class Cue(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val enabled: Boolean = true,
)

/** Stile dei sottotitoli (4 stili iOS × colori + font — ridotto ai parametri reali). */
data class SubtitleStyle(
    val preset: String = "clean", // clean | bold | box | highlight
    val color: Int = 0xFFFFFFFF.toInt(),
    val fontSizeSp: Float = 26f,
    val fontId: String = "modern",
    val posY: Float = 0.9f,
    val burnIn: Boolean = true,
) {
    fun toTitleDesign(cueText: String, startMs: Long, endMs: Long): TitleDesign = when (preset) {
        "bold" -> TitleDesign(
            text = cueText, preset = "clean", fontId = "black", fontSizeSp = fontSizeSp,
            colorStart = color, outlineWidthPx = 2.5f, startMs = startMs, endMs = endMs,
            posY = posY,
        )

        "box" -> TitleDesign(
            text = cueText, preset = "clean", fontId = fontId, fontSizeSp = fontSizeSp,
            colorStart = color, plate = "block", plateColor = 0xCC000000.toInt(),
            startMs = startMs, endMs = endMs, posY = posY,
        )

        "highlight" -> TitleDesign(
            text = cueText, preset = "clean", fontId = fontId, fontSizeSp = fontSizeSp,
            colorStart = 0xFF000000.toInt(), plate = "block", plateColor = color,
            startMs = startMs, endMs = endMs, posY = posY,
        )

        else -> TitleDesign(
            text = cueText, preset = "clean", fontId = fontId, fontSizeSp = fontSizeSp,
            colorStart = color, shadowBlurPx = 10f, startMs = startMs, endMs = endMs,
            posY = posY,
        )
    }
}

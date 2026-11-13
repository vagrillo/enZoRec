package it.agrilloit.turegistri.model

/**
 * Generatore del manifest di sessione (session.json): puro Kotlin, testabile.
 * Contiene tutto ciò che serve in postproduzione per allineare e riconoscere i file.
 */
object SessionManifest {

    /** Escape di una stringa JSON (senza virgolette esterne). */
    fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    data class CameraEntry(
        val cameraId: String,
        val isFront: Boolean,
        val sensorOrientation: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val file: String,
        val startedEpochMs: Long,
        val startedElapsedNanos: Long,
        val orientationHint: Int,
    )

    data class AudioTrackEntry(
        val file: String,
        val channels: Int,
        val sampleRate: Int,
        val micDescription: String,
    )

    data class AudioEntry(
        val strategy: String,
        val startedEpochMs: Long,
        val startedElapsedNanos: Long,
        val tracks: List<AudioTrackEntry>,
    )

    data class ScreenEntry(
        val file: String,
        val appAudioFile: String?,
    )

    data class CompositeEntry(
        val file: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val hasAudio: Boolean,
        val layout: String,
        val startedEpochMs: Long,
        val startedElapsedNanos: Long,
    )

    fun build(
        appVersion: String,
        manufacturer: String,
        model: String,
        androidRelease: String,
        sdkInt: Int,
        createdAtEpochMs: Long,
        layout: String,
        cameras: List<CameraEntry>,
        audio: AudioEntry?,
        composite: CompositeEntry?,
        screen: ScreenEntry? = null,
    ): String = buildString {
        append("{\n")
        append("  \"app\": \"TuRegistri\",\n")
        append("  \"appVersion\": \"${escape(appVersion)}\",\n")
        append("  \"device\": {\n")
        append("    \"manufacturer\": \"${escape(manufacturer)}\",\n")
        append("    \"model\": \"${escape(model)}\",\n")
        append("    \"androidRelease\": \"${escape(androidRelease)}\",\n")
        append("    \"sdkInt\": $sdkInt\n")
        append("  },\n")
        append("  \"createdAtEpochMs\": $createdAtEpochMs,\n")
        append("  \"layout\": \"${escape(layout)}\",\n")
        append("  \"cameras\": [\n")
        cameras.forEachIndexed { i, c ->
            append("    {\n")
            append("      \"cameraId\": \"${escape(c.cameraId)}\",\n")
            append("      \"facing\": \"${if (c.isFront) "front" else "back"}\",\n")
            append("      \"sensorOrientation\": ${c.sensorOrientation},\n")
            append("      \"resolution\": {\"width\": ${c.width}, \"height\": ${c.height}},\n")
            append("      \"fps\": ${c.fps},\n")
            append("      \"orientationHint\": ${c.orientationHint},\n")
            append("      \"file\": \"${escape(c.file)}\",\n")
            append("      \"startedEpochMs\": ${c.startedEpochMs},\n")
            append("      \"startedElapsedNanos\": ${c.startedElapsedNanos}\n")
            append("    }${if (i < cameras.size - 1) "," else ""}\n")
        }
        append("  ],\n")
        if (audio != null) {
            append("  \"audio\": {\n")
            append("    \"strategy\": \"${escape(audio.strategy)}\",\n")
            append("    \"startedEpochMs\": ${audio.startedEpochMs},\n")
            append("    \"startedElapsedNanos\": ${audio.startedElapsedNanos},\n")
            append("    \"sampleRate\": ${audio.tracks.firstOrNull()?.sampleRate ?: 48000},\n")
            append("    \"tracks\": [\n")
            audio.tracks.forEachIndexed { i, t ->
                append("      {\n")
                append("        \"file\": \"${escape(t.file)}\",\n")
                append("        \"channels\": ${t.channels},\n")
                append("        \"sampleRate\": ${t.sampleRate},\n")
                append("        \"mic\": \"${escape(t.micDescription)}\"\n")
                append("      }${if (i < audio.tracks.size - 1) "," else ""}\n")
            }
            append("    ]\n")
            append("  },\n")
        } else {
            append("  \"audio\": null,\n")
        }
        if (screen != null) {
            append("  \"screenDisplay\": {\n")
            append("    \"file\": \"${escape(screen.file)}\",\n")
            append("    \"appAudioFile\": ${if (screen.appAudioFile != null) "\"${escape(screen.appAudioFile)}\""
                else "null"},\n")
            append("  },\n")
        }
        if (composite != null) {
            append("  \"composite\": {\n")
            append("    \"file\": \"${escape(composite.file)}\",\n")
            append("    \"resolution\": {\"width\": ${composite.width}, \"height\": ${composite.height}},\n")
            append("    \"fps\": ${composite.fps},\n")
            append("    \"hasAudio\": ${composite.hasAudio},\n")
            append("    \"layout\": \"${escape(composite.layout)}\",\n")
            append("    \"startedEpochMs\": ${composite.startedEpochMs},\n")
            append("    \"startedElapsedNanos\": ${composite.startedElapsedNanos}\n")
            append("  }\n")
        } else {
            append("  \"composite\": null\n")
        }
        append("}\n")
    }
}

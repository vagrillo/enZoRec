package it.agrilloit.turegistri.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Esporta il montaggio come documento OpenTimelineIO (JSON) — port della
 * `Timeline.otio` iOS per il conform in DaVinci Resolve. Schema OTIO 1.0:
 * Timeline → Stack → Track (Video/Audio) → Clip con source_range.
 */
object OtioExport {

    data class OtioClip(
        val name: String,
        val sourcePath: String,
        val startMs: Long, // nel sorgente
        val durationMs: Long,
        val timelineStartMs: Long,
        val speed: Float = 1f,
    )

    data class OtioMarker(val name: String, val timeMs: Long)

    fun build(
        projectName: String,
        videoClips: List<OtioClip>,
        markers: List<OtioMarker> = emptyList(),
        fps: Double = 30.0,
    ): String {
        val timeline = JSONObject()
        timeline.put("OTIO_SCHEMA", "Timeline.2")
        timeline.put("name", projectName)
        timeline.put("global_start_time", JSONObject.NULL)
        timeline.put("metadata", JSONObject())

        val stack = JSONObject()
        stack.put("OTIO_SCHEMA", "Stack.2")
        stack.put("name", "tracks")
        stack.put("source_range", JSONObject.NULL)
        stack.put("metadata", JSONObject())
        stack.put("effects", JSONArray())
        stack.put("markers", JSONArray())

        val videoTrack = JSONObject()
        videoTrack.put("OTIO_SCHEMA", "Track.2")
        videoTrack.put("name", "V1")
        videoTrack.put("kind", "Video")
        videoTrack.put("source_range", JSONObject.NULL)
        videoTrack.put("metadata", JSONObject())
        videoTrack.put("effects", JSONArray())
        videoTrack.put("markers", JSONArray())

        val children = JSONArray()
        for (c in videoClips) {
            val startTime = JSONObject()
                startTime.put("OTIO_SCHEMA", "RationalTime.1")
                startTime.put("rate", fps)
                startTime.put("value", c.startMs / 1000.0 * fps)
                val durTime = JSONObject()
                durTime.put("OTIO_SCHEMA", "RationalTime.1")
                durTime.put("rate", fps)
                durTime.put("value", c.durationMs / 1000.0 * fps)
                val sourceRange = JSONObject()
                sourceRange.put("OTIO_SCHEMA", "TimeRange.1")
                sourceRange.put("start_time", startTime)
                sourceRange.put("duration", durTime)
                val zeroTime = JSONObject()
                zeroTime.put("OTIO_SCHEMA", "RationalTime.1")
                zeroTime.put("rate", fps)
                zeroTime.put("value", 0.0)
                val availDur = JSONObject()
                availDur.put("OTIO_SCHEMA", "RationalTime.1")
                availDur.put("rate", fps)
                availDur.put("value", c.durationMs / 1000.0 * fps)
                val availRange = JSONObject()
                availRange.put("OTIO_SCHEMA", "TimeRange.1")
                availRange.put("start_time", zeroTime)
                availRange.put("duration", availDur)
                val mediaRef = JSONObject()
                mediaRef.put("OTIO_SCHEMA", "ExternalReference.1")
                mediaRef.put("target_url", c.sourcePath)
                mediaRef.put("available_range", availRange)
                mediaRef.put("metadata", JSONObject())
                val clipMeta = JSONObject()
                clipMeta.put("yourec_speed", c.speed.toDouble())
                val clip = JSONObject()
                clip.put("OTIO_SCHEMA", "Clip.2")
                clip.put("name", c.name)
                clip.put("source_range", sourceRange)
                clip.put("media_reference", mediaRef)
                clip.put("metadata", clipMeta)
                clip.put("effects", JSONArray())
                clip.put("markers", JSONArray())
            children.put(clip)
        }
        videoTrack.put("children", children)

        val audioTrack = JSONObject()
        audioTrack.put("OTIO_SCHEMA", "Track.2")
        audioTrack.put("name", "A1")
        audioTrack.put("kind", "Audio")
        audioTrack.put("source_range", JSONObject.NULL)
        audioTrack.put("metadata", JSONObject())
        audioTrack.put("effects", JSONArray())
        audioTrack.put("markers", JSONArray())
        audioTrack.put("children", JSONArray())

        val tracks = JSONArray()
        tracks.put(videoTrack)
        tracks.put(audioTrack)
        stack.put("children", tracks)

        val markerArr = JSONArray()
        for (m in markers) {
            markerArr.put(
                JSONObject()
                    .put("OTIO_SCHEMA", "Marker.2")
                    .put("name", m.name)
                    .put("metadata", JSONObject())
                    .put("color", JSONObject.NULL)
                        .put("marked_range", JSONObject()
                        .put("OTIO_SCHEMA", "TimeRange.1")
                        .put("start_time", JSONObject()
                            .put("OTIO_SCHEMA", "RationalTime.1")
                            .put("rate", fps)
                            .put("value", m.timeMs / 1000.0 * fps))
                        .put("duration", JSONObject()
                            .put("OTIO_SCHEMA", "RationalTime.1")
                            .put("rate", fps)
                            .put("value", 1.0)))
            )
        }

        timeline.put("tracks", stack)
        stack.put("markers", markerArr)

        // Racchiude nel serialization wrapper OTIO standard.
        // toString(2) è stub nei test JVM: JSON compatibile anche compatto
        return timeline.toString()
    }
}

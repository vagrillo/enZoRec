package it.agrilloit.turegistri

import it.agrilloit.turegistri.model.SessionManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManifestTest {

    @Test
    fun `manifest completo contiene tutte le sezioni`() {
        val json = SessionManifest.build(
            appVersion = "1.0.0",
            manufacturer = "TestCorp",
            model = "TestModel X",
            androidRelease = "14",
            sdkInt = 34,
            createdAtEpochMs = 1730000000000L,
            layout = "SPLIT_V",
            cameras = listOf(
                SessionManifest.CameraEntry(
                    cameraId = "0",
                    isFront = false,
                    sensorOrientation = 90,
                    width = 1280,
                    height = 720,
                    fps = 30,
                    file = "cam0.mp4",
                    startedEpochMs = 1730000000100L,
                    startedElapsedNanos = 123456789L,
                    orientationHint = 90,
                ),
                SessionManifest.CameraEntry(
                    cameraId = "1",
                    isFront = true,
                    sensorOrientation = 270,
                    width = 1280,
                    height = 720,
                    fps = 30,
                    file = "cam1.mp4",
                    startedEpochMs = 1730000000110L,
                    startedElapsedNanos = 133456789L,
                    orientationHint = 270,
                ),
            ),
            audio = SessionManifest.AudioEntry(
                strategy = "multicanale (3 canali)",
                startedEpochMs = 1730000000090L,
                startedElapsedNanos = 113456789L,
                tracks = listOf(
                    SessionManifest.AudioTrackEntry(
                        file = "mic_multicanale.wav",
                        channels = 3,
                        sampleRate = 48000,
                        micDescription = "mic multicanale",
                    ),
                ),
            ),
            composite = SessionManifest.CompositeEntry(
                file = "composito.mp4",
                width = 720,
                height = 1280,
                fps = 30,
                hasAudio = true,
                layout = "SPLIT_V",
                startedEpochMs = 1730000000120L,
                startedElapsedNanos = 143456789L,
            ),
        )

        assertTrue(json.contains("\"app\": \"TuRegistri\""))
        assertTrue(json.contains("\"model\": \"TestModel X\""))
        assertTrue(json.contains("\"layout\": \"SPLIT_V\""))
        assertTrue(json.contains("\"file\": \"cam0.mp4\""))
        assertTrue(json.contains("\"file\": \"cam1.mp4\""))
        assertTrue(json.contains("\"facing\": \"front\""))
        assertTrue(json.contains("\"facing\": \"back\""))
        assertTrue(json.contains("\"file\": \"mic_multicanale.wav\""))
        assertTrue(json.contains("\"channels\": 3"))
        assertTrue(json.contains("\"file\": \"composito.mp4\""))
        assertTrue(json.contains("\"hasAudio\": true"))
        assertFalse(json.contains("\"audio\": null"))
        assertFalse(json.contains("\"composite\": null"))
    }

    @Test
    fun `manifest senza audio e composito usa null`() {
        val json = SessionManifest.build(
            appVersion = "1.0.0",
            manufacturer = "TestCorp",
            model = "M",
            androidRelease = "11",
            sdkInt = 30,
            createdAtEpochMs = 1L,
            layout = "SINGLE_A",
            cameras = emptyList(),
            audio = null,
            composite = null,
        )
        assertTrue(json.contains("\"audio\": null"))
        assertTrue(json.contains("\"composite\": null"))
        assertTrue(json.contains("\"cameras\": ["))
    }

    @Test
    fun `escape gestisce virgolette backslash e newline`() {
        assertEquals("a\\\"b", SessionManifest.escape("a\"b"))
        assertEquals("a\\\\b", SessionManifest.escape("a\\b"))
        assertEquals("a\\nb", SessionManifest.escape("a\nb"))
        assertEquals("a\\tb", SessionManifest.escape("a\tb"))
        assertEquals("a\\u0001b", SessionManifest.escape("a\u0001b"))
        assertEquals("àèì", SessionManifest.escape("àèì"))
    }
}

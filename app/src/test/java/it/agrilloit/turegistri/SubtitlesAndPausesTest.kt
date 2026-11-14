package it.agrilloit.turegistri

import it.agrilloit.turegistri.audio.PauseDetector
import it.agrilloit.turegistri.subtitles.Cue
import it.agrilloit.turegistri.subtitles.SubtitlesEngine
import it.agrilloit.turegistri.subtitles.SubtitlesEngine.Word
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesAndPausesTest {

    @Test
    fun `group cues on gap and sentence punctuation`() {
        val words = listOf(
            SubtitlesEngine.Word("ciao", 0, 300),
            SubtitlesEngine.Word("sono", 400, 700),
            // gap > 320ms → nuova frase
            SubtitlesEngine.Word("qui", 1500, 1800),
            // punteggiatura finale → break dopo
            SubtitlesEngine.Word("bene.", 1850, 2200),
            SubtitlesEngine.Word("ok", 2300, 2500),
        )
        val cues = SubtitlesEngine.groupCues(words, timelineOffsetMs = 5000)
        assertEquals(3, cues.size)
        // offset applicato
        assertEquals(5000L, cues[0].startMs)
        assertEquals("ciao sono", cues[0].text)
        assertEquals("qui bene.", cues[1].text)
        assertEquals("ok", cues[2].text)
    }

    @Test
    fun `max 16 words per cue`() {
        val words = (1..20).mapIndexed { i, w -> SubtitlesEngine.Word("p$i", i * 100L, i * 100L + 60) }
        val cues = SubtitlesEngine.groupCues(words)
        assertTrue(cues.all { it.text.split(" ").size <= 16 })
        assertEquals(2, cues.size)
    }

    @Test
    fun `srt format is correct`() {
        val srt = SubtitlesEngine.toSrt(listOf(Cue("1", 1000, 2500, "testo")))
        assertTrue(srt.startsWith("1\n"))
        assertTrue(srt.contains("00:00:01,000 --> 00:00:02,500"))
        assertTrue(srt.contains("testo"))
    }

    @Test
    fun `pause detector finds silence runs with padding`() {
        // 10 s: 1s voce, 3s silenzio, 3s voce, 3s silenzio → peaks @100ms
        val peaks = ArrayList<Float>()
        repeat(10) { peaks.add(0.5f) }   // 0-1s voce
        repeat(30) { peaks.add(0.01f) }  // 1-4s silenzio
        repeat(30) { peaks.add(0.4f) }   // 4-7s voce
        repeat(30) { peaks.add(0.005f) } // 7-10s silenzio
        val windows = PauseDetector.detect(peaks, 10_000, PauseDetector.Params(minSilenceMs = 700))
        assertEquals(2, windows.size)
        // padding 120/150ms
        assertEquals(1000L - 120, windows[0].startMs)
        assertEquals(4000L + 150, windows[0].endMs)
        assertTrue(windows[1].startMs >= 7000L - 120)
        assertEquals(10_000L, windows[1].endMs)
    }

    @Test
    fun `otio document has timeline and clips`() {
        val otio = it.agrilloit.turegistri.model.OtioExport.build(
            projectName = "P",
            videoClips = listOf(
                it.agrilloit.turegistri.model.OtioExport.OtioClip(
                    "c1", "/tmp/a.mp4", 500, 2000, 0,
                )
            ),
        )
        val obj = JSONObject(otio)
        assertEquals("Timeline.2", obj.getString("OTIO_SCHEMA"))
        val tracks = obj.getJSONObject("tracks").getJSONArray("children")
        val video = tracks.getJSONObject(0).getJSONArray("children")
        assertEquals(1, video.length())
        assertEquals("c1", video.getJSONObject(0).getString("name"))
    }
}

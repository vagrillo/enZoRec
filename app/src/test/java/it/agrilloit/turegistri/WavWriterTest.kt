package it.agrilloit.turegistri

import it.agrilloit.turegistri.audio.WavWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WavWriterTest {

    @Test
    fun `header contains RIFF WAVE fmt data and correct sizes`() {
        val dir = createTempDir("wavtest")
        val file = File(dir, "test.wav")
        val sampleRate = 48000
        val channels = 2

        val writer = WavWriter(file, sampleRate, channels)
        val samples = shortArrayOf(1, -1, 32767, -32768, 100, -100, 5, 5) // 4 frame stereo
        writer.write(samples, samples.size)
        writer.close()

        assertTrue(file.length() >= 44L)
        val bytes = file.readBytes()
        assertEquals(44L + samples.size * 2L, file.length())

        // Chunk ID e format
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))

        // RIFF size = 36 + dataSize
        val riffSize = leInt(bytes, 4)
        assertEquals(36 + samples.size * 2, riffSize)

        // Data size
        val dataSize = leInt(bytes, 40)
        assertEquals(samples.size * 2, dataSize)

        // fmt block: PCM(1), 2 canali, 48000 Hz, byte rate 48000*2*2, block align 4, 16 bit
        assertEquals(16, leInt(bytes, 16))
        assertEquals(1, leShort(bytes, 20))
        assertEquals(channels, leShort(bytes, 22))
        assertEquals(sampleRate, leInt(bytes, 24))
        assertEquals(sampleRate * channels * 2, leInt(bytes, 28))
        assertEquals(channels * 2, leShort(bytes, 32))
        assertEquals(16, leShort(bytes, 34))

        // Payload scritto correttamente (little endian)
        assertEquals(1, leShort(bytes, 44))
        assertEquals(-1, leShort(bytes, 46))
        assertEquals(32767, leShort(bytes, 48))
        assertEquals(-32768, leShort(bytes, 50))
    }

    @Test
    fun `multiple writes accumulate data size`() {
        val dir = createTempDir("wavtest")
        val file = File(dir, "multi.wav")
        WavWriter(file, 48000, 1).use { w ->
            w.write(ShortArray(1000) { 100 }, 1000)
            w.write(ShortArray(500) { -100 }, 500)
        }
        val bytes = file.readBytes()
        assertEquals(1500 * 2, leInt(bytes, 40))
        assertEquals(44 + 1500 * 2, bytes.size)
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
}

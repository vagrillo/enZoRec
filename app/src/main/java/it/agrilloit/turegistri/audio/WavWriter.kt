package it.agrilloit.turegistri.audio

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scrittore WAV (RIFF) PCM 16-bit little-endian, multicanale.
 * Scrive l'intestazione segnaposto subito e la completa in [close] con le
 * dimensioni reali dei dati (seek indietro).
 */
class WavWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int,
) : Closeable {

    private val fileOut = FileOutputStream(file)
    private val out = fileOut.buffered(BUFFER_SIZE)
    private val scratch: ByteBuffer =
        ByteBuffer.allocate(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    private var dataBytes = 0L
    private var closed = false

    init {
        require(channels in 1..32) { "Numero canali non valido: $channels" }
        writeHeaderPlaceholder()
    }

    /** Aggiunge campioni PCM 16-bit (n campioni totali, interleaved sui canali). */
    @Synchronized
    @Throws(IOException::class)
    fun write(samples: ShortArray, count: Int) {
        if (closed || count <= 0) return
        scratch.clear()
        var i = 0
        while (i < count) {
            if (scratch.remaining() < 2) {
                flushScratch()
            }
            scratch.putShort(samples[i])
            i++
        }
        flushScratch()
    }

    private fun flushScratch() {
        val n = scratch.position()
        if (n > 0) {
            out.write(scratch.array(), 0, n)
            dataBytes += n
            scratch.clear()
        }
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        if (closed) return
        closed = true
        flushScratch()
        out.flush()
        // Completa le dimensioni nell'header.
        val riffSize = 36L + dataBytes
        val fd = fileOut.channel
        fd.position(4)
        writeLeInt(fd, riffSize)
        fd.position(40)
        writeLeInt(fd, dataBytes)
        out.close()
    }

    private fun writeHeaderPlaceholder() {
        // Intestazione con dimensioni a zero: verranno completate in close().
        val header = ByteArray(HEADER_SIZE)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36) // da aggiornare
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // dimensione blocco fmt
        buf.putShort(1) // PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * channels * 2) // byte rate
        buf.putShort((channels * 2).toShort()) // block align
        buf.putShort(16) // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(0) // da aggiornare
        out.write(header)
    }

    private fun writeLeInt(channel: java.nio.channels.FileChannel, value: Long) {
        val b = ByteArray(4)
        b[0] = (value and 0xFF).toByte()
        b[1] = ((value shr 8) and 0xFF).toByte()
        b[2] = ((value shr 16) and 0xFF).toByte()
        b[3] = ((value shr 24) and 0xFF).toByte()
        val buf = ByteBuffer.wrap(b)
        while (buf.hasRemaining()) {
            channel.write(buf)
        }
    }

    private companion object {
        const val HEADER_SIZE = 44
        const val BUFFER_SIZE = 64 * 1024
    }
}

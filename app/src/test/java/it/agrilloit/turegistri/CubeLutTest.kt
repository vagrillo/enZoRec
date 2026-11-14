package it.agrilloit.turegistri

import it.agrilloit.turegistri.model.CubeLut
import it.agrilloit.turegistri.model.CubeLutData
import it.agrilloit.turegistri.model.CubeLutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutTest {

    @Test
    fun `parse 1D curve and synthesize cube`() {
        val n = 4
        val text = buildString {
            appendLine("TITLE \"Curve test\"")
            appendLine("LUT_1D_SIZE $n")
            for (i in 0 until n) {
                appendLine("${i / (n - 1.0)} 0.0 1.0")
            }
        }
        val parsed = CubeLut.parse(text)
        assertTrue(parsed.lut is CubeLutData.OneD)
        val lut = parsed.lut as CubeLutData.OneD
        assertEquals(n, lut.size)
        assertEquals(1.0f, lut.red[n - 1], 1e-4f)
        assertEquals(0.0f, lut.green[1], 1e-4f)

        val cube = CubeLut.toMedia3Cube(lut)
        assertEquals(33, cube.size)
        assertEquals(33, cube[0].size)
        assertEquals(33, cube[0][0].size)
        // rosso cresce lungo il primo indice (curve per canale)
        val first = cube[0][0][0]
        val last = cube[32][0][0]
        assertTrue(last > first)
    }

    @Test
    fun `parse 3D cube with red-fastest order`() {
        val n = 3
        val text = buildString {
            appendLine("LUT_3D_SIZE $n")
            // righe: r varia più veloce → (0,0,0), (1,0,0), (2,0,0), (0,1,0)…
            val vals = ArrayList<Triple<Float, Float, Float>>()
            for (b in 0 until n) {
                for (g in 0 until n) {
                    for (r in 0 until n) {
                        vals.add(Triple(r / 2f, g / 2f, b / 2f))
                    }
                }
            }
            vals.forEach { appendLine("${it.first} ${it.second} ${it.third}") }
        }
        val parsed = CubeLut.parse(text)
        val lut = parsed.lut as CubeLutData.ThreeD
        assertEquals(n, lut.size)
        // cube[red][green][blue]: (r=2,g=0,b=0) → R=1.0
        assertEquals(1.0f, lut.data[2][0][0][0], 1e-5f)
        // (r=0,g=2,b=0) → G=1.0
        assertEquals(1.0f, lut.data[0][2][0][1], 1e-5f)

        val cube = CubeLut.toMedia3Cube(lut)
        assertEquals(3, cube.size)
    }

    @Test
    fun `invalid cube throws`() {
        try {
            CubeLut.parse("TITLE niente\n1.0 0.0 0.5")
            throw AssertionError("attesa CubeLutException")
        } catch (e: CubeLutException) {
            assertTrue(e.message!!.contains("dimensione"))
        }
    }
}

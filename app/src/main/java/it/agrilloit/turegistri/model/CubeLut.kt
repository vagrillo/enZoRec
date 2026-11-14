package it.agrilloit.turegistri.model

/** LUT parsata: 1D (curve per canale) o 3D (cubo `data[red][green][blue]`). */
abstract class CubeLutData {
    /** LUT 1D: tre curve per canale, ciascuna [size] punti 0..1. */
    data class OneD(val size: Int, val red: FloatArray, val green: FloatArray, val blue: FloatArray) : CubeLutData()

    /** LUT 3D: `data[red][green][blue] = FloatArray(3)` 0..1. */
    data class ThreeD(val size: Int, val data: Array<Array<Array<FloatArray>>>) : CubeLutData()
}

data class CubeLutParsed(val title: String, val lut: CubeLutData)

class CubeLutException(message: String) : Exception(message)

/**
 * Lettore di file LUT `.cube` (port del CubeReader iOS): supporta LUT 1D
 * (`LUT_1D_SIZE`, curve per canale) e 3D (`LUT_3D_SIZE`), con `TITLE`,
 * `DOMAIN_MIN/MAX`, commenti `#`. Ordine righe 3D secondo la specifica Adobe:
 * il canale ROSSO varia più velocemente (prima riga = (0,0,0), poi (1,0,0)…).
 *
 * L'output per media3 è `Int[N][N][N]` con `cube[red][green][blue]` = colore
 * packed (layout atteso da `SingleColorLut.createFromCube`).
 */
object CubeLut {

    /** Packing ARGB (equivalente di Color.rgb, senza dipendenza android — testabile JVM). */
    private fun packRgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    fun parse(text: String): CubeLutParsed {
        var title = ""
        var size1d = -1
        var size3d = -1
        val values = ArrayList<FloatArray>(4096)

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            when {
                line.startsWith("TITLE", ignoreCase = true) ->
                    title = line.substringAfter(' ').trim().trim('"')

                line.startsWith("LUT_1D_SIZE", ignoreCase = true) ->
                    size1d = line.substringAfter(' ').trim().toIntOrNull()
                        ?: throw CubeLutException("LUT_1D_SIZE non valido")

                line.startsWith("LUT_3D_SIZE", ignoreCase = true) ->
                    size3d = line.substringAfter(' ').trim().toIntOrNull()
                        ?: throw CubeLutException("LUT_3D_SIZE non valido")

                line.startsWith("LUT_1D_INPUT_RANGE", ignoreCase = true) ||
                    line.startsWith("LUT_3D_INPUT_RANGE", ignoreCase = true) ||
                    line.startsWith("DOMAIN_MIN", ignoreCase = true) ||
                    line.startsWith("DOMAIN_MAX", ignoreCase = true) -> Unit

                else -> {
                    val parts = line.split(Regex("[\\s,]+"))
                    if (parts.size >= 3) {
                        val r = parts[0].toFloatOrNull() ?: continue
                        val g = parts[1].toFloatOrNull() ?: continue
                        val b = parts[2].toFloatOrNull() ?: continue
                        values.add(floatArrayOf(r, g, b))
                    }
                }
            }
        }

        if (size1d > 0 && size3d > 0) {
            throw CubeLutException("Il file dichiara sia LUT_1D_SIZE sia LUT_3D_SIZE")
        }
        if (size1d > 0) {
            if (values.size < size1d) throw CubeLutException("LUT 1D incompleta")
            if (size1d > 16384) throw CubeLutException("LUT 1D troppo grande (max 16384)")
            val red = FloatArray(size1d)
            val green = FloatArray(size1d)
            val blue = FloatArray(size1d)
            for (i in 0 until size1d) {
                red[i] = values[i][0]
                green[i] = values[i][1]
                blue[i] = values[i][2]
            }
            return CubeLutParsed(title, CubeLutData.OneD(size1d, red, green, blue))
        }
        if (size3d > 0) {
            if (size3d < 2 || size3d > 65) throw CubeLutException("LUT_3D_SIZE fuori range (2..65)")
            if (values.size < size3d * size3d * size3d) throw CubeLutException("LUT 3D incompleta")
            val n = size3d
            // Righe ordinate con ROSSO che varia più velocemente:
            // linea i → r = i % N, g = (i / N) % N, b = i / N².
            val data = Array(n) { Array(n) { Array(n) { FloatArray(3) } } }
            for (i in 0 until n * n * n) {
                val r = i % n
                val g = (i / n) % n
                val b = i / (n * n)
                data[r][g][b] = values[i]
            }
            return CubeLutParsed(title, CubeLutData.ThreeD(n, data))
        }
        throw CubeLutException("Nessuna dimensione LUT dichiarata")
    }

    /**
     * Converte la LUT nel formato media3 `Int[N][N][N]` (colore packed,
     * `cube[red][green][blue]`). Per le LUT 1D sintetizza un cubo 33³ dalle
     * tre curve (matematicamente equivalente: la 1D è separabile per canale).
     */
    fun toMedia3Cube(lut: CubeLutData): Array<Array<IntArray>> = when (lut) {
        is CubeLutData.ThreeD -> {
            val n = lut.size
            Array(n) { r ->
                Array(n) { g ->
                    IntArray(n) { b ->
                        val v = lut.data[r][g][b]
                        packRgb(
                            (v[0].coerceIn(0f, 1f) * 255f).toInt(),
                            (v[1].coerceIn(0f, 1f) * 255f).toInt(),
                            (v[2].coerceIn(0f, 1f) * 255f).toInt(),
                        )
                    }
                }
            }
        }

        is CubeLutData.OneD -> {
            // Ricampiona le curve su 33 punti e costruisce il cubo separabile.
            val n = 33
            fun sample(curve: FloatArray, t: Float): Float {
                val x = t * (curve.size - 1)
                val i0 = x.toInt().coerceIn(0, curve.size - 1)
                val i1 = (i0 + 1).coerceAtMost(curve.size - 1)
                val f = x - i0
                return curve[i0] * (1f - f) + curve[i1] * f
            }
            Array(n) { r ->
                Array(n) { g ->
                    IntArray(n) { b ->
                        packRgb(
                            (sample(lut.red, r / (n - 1f)).coerceIn(0f, 1f) * 255f).toInt(),
                            (sample(lut.green, g / (n - 1f)).coerceIn(0f, 1f) * 255f).toInt(),
                            (sample(lut.blue, b / (n - 1f)).coerceIn(0f, 1f) * 255f).toInt(),
                        )
                    }
                }
            }
        }

        else -> throw CubeLutException("LUT non supportata")
    }
}

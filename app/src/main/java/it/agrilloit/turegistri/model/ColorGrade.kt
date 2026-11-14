package it.agrilloit.turegistri.model

import kotlin.math.pow

/**
 * Color grading come matrice RGB 4x4 lineare, compatibile con
 * `androidx.media3.effect.RgbMatrix` (column-major: element(col,row) =
 * array[col*4 + row]; out = M · [r,g,b,1]).
 *
 * Controlli lineari: exposure (EV), contrast, saturation, warmth.
 * Preset stile iOS: noir / bleach bypass / day for night.
 */
object ColorGrade {

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** Composizione: restituisce p∘q — applica prima q, poi p. */
    fun compose(p: FloatArray, q: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var acc = 0f
                for (k in 0 until 4) {
                    acc += p[k * 4 + row] * q[col * 4 + k]
                }
                out[col * 4 + row] = acc
            }
        }
        return out
    }

    /** Esposizione in EV (±2): fattore lineare 2^ev su RGB. */
    fun exposure(ev: Float): FloatArray {
        val f = 2f.pow(ev)
        return scale(f, f, f)
    }

    /** Contrasto attorno al mid-gray 0.5 (1 = neutro, 0..2). */
    fun contrast(c: Float): FloatArray = floatArrayOf(
        c, 0f, 0f, 0f,
        0f, c, 0f, 0f,
        0f, 0f, c, 0f,
        0.5f * (1f - c), 0.5f * (1f - c), 0.5f * (1f - c), 1f,
    )

    /** Saturazione (0 = B/N, 1 = neutro, 2 = ipersaturo), luma Rec.709. */
    fun saturation(s: Float): FloatArray = floatArrayOf(
        (1f - s) * 0.2126f + s, (1f - s) * 0.2126f, (1f - s) * 0.2126f, 0f,
        (1f - s) * 0.7152f, (1f - s) * 0.7152f + s, (1f - s) * 0.7152f, 0f,
        (1f - s) * 0.0722f, (1f - s) * 0.0722f, (1f - s) * 0.0722f + s, 0f,
        0f, 0f, 0f, 1f,
    )

    /** Warmth (−1 freddo … +1 caldo): bilancia R e B. */
    fun warmth(w: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        w * 0.08f, 0f, -w * 0.08f, 1f,
    )

    fun scale(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(
        r, 0f, 0f, 0f,
        0f, g, 0f, 0f,
        0f, 0f, b, 0f,
        0f, 0f, 0f, 1f,
    )

    /** Noir: desaturazione totale + contrasto leggero. */
    fun noir(): FloatArray = compose(contrast(1.15f), saturation(0f))

    /** Bleach bypass: desat parziale, contrasto alto, lieve lift. */
    fun bleachBypass(): FloatArray = compose(
        compose(contrast(1.35f), exposure(0.12f)),
        saturation(0.35f),
    )

    /** Day for night: desat, blu, scurimento. */
    fun dayForNight(): FloatArray = compose(
        compose(saturation(0.35f), warmth(-0.5f)),
        exposure(-0.6f),
    )

    /** Applica i 4 controlli del pannello Color Correction, in ordine fisso. */
    fun applyControls(
        exposureEv: Float,
        contrast: Float,
        saturation: Float,
        warmth: Float,
    ): FloatArray {
        var m = identity()
        m = compose(m, contrast(contrast))
        m = compose(m, warmth(warmth))
        m = compose(m, saturation(saturation))
        m = compose(m, exposure(exposureEv))
        return m
    }

    fun isIdentity(m: FloatArray): Boolean {
        val id = identity()
        for (i in m.indices) if (kotlin.math.abs(m[i] - id[i]) > 1e-4f) return false
        return true
    }
}

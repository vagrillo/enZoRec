package it.agrilloit.turegistri.model

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stabilizzazione offline (port light di `VideoStabilizer` iOS):
 *  - campiona frame ridotti (~256 px) a 8 fps nella finestra del clip;
 *  - insegna i punti su una griglia 12×8 con block matching (SSD 9×9, ±10 px);
 *  - stima la trasformazione di similarità (dx, dy, rotazione, scala) tra frame
 *    consecutivi (Horn closed-form + filtro errori);
 *  - compone in coordinate "mondo" e attenua con media pesata gaussiana;
 *  - calcola il crop zoom dalla massima escursione dei corner.
 *
 * Livelli iOS: Off / Light / Standard / Strong (σ = 0.12 / 0.28 / 0.5 s).
 */
object Stabilizer {

    data class Pose(val tMs: Long, val dx: Float, val dy: Float, val angle: Float, val scale: Float)

    data class Result(
        val poses: List<Pose>,
        val cropZoom: Float,
        val level: Int,
        val trimStartMs: Long,
        val trimEndMs: Long,
        val sourceMs: Long,
    )

    val LEVEL_SIGMA = floatArrayOf(0f, 0.12f, 0.28f, 0.5f)
    val LEVEL_LABELS = listOf("Off", "Light", "Standard", "Strong")

    private const val SAMPLE_FPS = 8
    private const val LUMA_W = 256
    private const val GRID_X = 12
    private const val GRID_Y = 8
    private const val SEARCH = 10
    private const val PATCH = 4 // 9×9 → raggio 4

    @Volatile
    var cancelled = false

    /** Analizza la finestra del clip; ritorna il risultato (o null se off). */
    fun analyze(
        videoPath: String,
        trimStartMs: Long,
        trimEndMs: Long,
        level: Int,
        onProgress: (Float) -> Unit = {},
    ): Result? {
        if (level <= 0) return null
        cancelled = false
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)
            val clipMs = (trimEndMs - trimStartMs).coerceAtLeast(200)
            val stepMs = 1000L / SAMPLE_FPS
            val frames = ArrayList<ByteArray>()
            val times = ArrayList<Long>()
            val sampleH = (LUMA_W * 9 / 16).coerceAtLeast(48) // aspect non critico per il tracking
            var t = trimStartMs
            var i = 0
            val totalFrames = (clipMs / stepMs).toInt() + 1
            while (t < trimEndMs && !cancelled) {
                val bmp = runCatching {
                    retriever.getScaledFrameAtTime(
                        t * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                        LUMA_W, sampleH,
                    )
                }.getOrNull()
                if (bmp != null) {
                    frames.add(toLuma(bmp))
                    times.add(t)
                }
                t += stepMs
                i++
                onProgress((i.toFloat() / totalFrames).coerceIn(0f, 1f) * 0.8f)
            }
            if (frames.size < 3) return null

            val frameH = frames[0].size / LUMA_W
            // Delta di similarità tra frame consecutivi + composizione in mondo.
            val poses = ArrayList<Pose>()
            var cumDx = 0f
            var cumDy = 0f
            var cumAngle = 0f
            var logScale = 0f
            for (k in 1 until frames.size) {
                if (cancelled) return null
                val delta = matchFrames(frames[k - 1], frames[k]) ?: continue
                // compone: angoli sommati, log-scale sommata, traslazione ruotata
                val s = exp(logScale)
                val cosA = cos(cumAngle)
                val sinA = sin(cumAngle)
                cumDx += (cosA * delta[0] - sinA * delta[1])
                cumDy += (sinA * delta[0] + cosA * delta[1])
                cumAngle += delta[2]
                logScale += delta[3]
                poses.add(
                    Pose(
                        times[k],
                        cumDx / LUMA_W, cumDy / frameH.toFloat(),
                        cumAngle, exp(logScale),
                    )
                )
                if (k % 16 == 0) onProgress(0.8f + 0.15f * k / frames.size)
            }
            if (poses.isEmpty()) return null

            // Smoothing gaussiano per parametro (in tempo).
            val sigmaS = LEVEL_SIGMA[level.coerceIn(1, 3)]
            val smoothed = smooth(poses, sigmaS * SAMPLE_FPS)

            // Crop zoom dai corner (massimo sull'intera sequenza): per ogni corner
            // del frame stabilizzato, il punto rimappato sull'originale deve restare
            // dentro [0,1] dopo il crop (p'' = 0.5 + (p-0.5)/zoom).
            var crop = 1f
            val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)
            for (p in smoothed) {
                val ca = cos(-p.angle)
                val sa = sin(-p.angle)
                val invS = 1f / max(p.scale, 1e-4f)
                for ((cx, cy) in corners) {
                    val x = cx - p.dx
                    val y = cy - p.dy
                    val ox = (ca * x - sa * y) * invS
                    val oy = (sa * x + ca * y) * invS
                    val need = max(abs(ox - 0.5f), abs(oy - 0.5f)) / 0.5f
                    crop = max(crop, need)
                }
            }
            crop = crop.coerceIn(1f, 1.25f)

            onProgress(1f)
            return Result(smoothed, crop, level, trimStartMs, trimEndMs, videoPath.hashCode().toLong())
        } finally {
            retriever.release()
        }
    }

    /** Media pesata gaussiana con kernel temporale a finestra fissa. */
    private fun smooth(poses: List<Pose>, windowFrames: Float): List<Pose> {
        if (windowFrames < 1.5f) return poses
        val half = windowFrames.toInt().coerceAtLeast(1)
        val out = ArrayList<Pose>(poses.size)
        for (i in poses.indices) {
            var wSum = 0f
            var dx = 0f
            var dy = 0f
            var ang = 0f
            var sc = 0f
            for (j in max(0, i - half)..min(poses.size - 1, i + half)) {
                val d = (i - j).toFloat()
                val w = exp(-0.5f * (d / (windowFrames * 0.6f)) * (d / (windowFrames * 0.6f)))
                wSum += w
                dx += poses[j].dx * w
                dy += poses[j].dy * w
                ang += poses[j].angle * w
                sc += poses[j].scale * w
            }
            out.add(Pose(poses[i].tMs, dx / wSum, dy / wSum, ang / wSum, sc / wSum))
        }
        return out
    }

    private fun toLuma(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
        }
        return out
    }

    /**
     * Block matching su griglia + closed-form similarity: ritorna
     * [dx, dy, angle, logScale] del frame k rispetto a k-1, o null se poco affidabile.
     */
    private fun matchFrames(prev: ByteArray, cur: ByteArray): FloatArray? {
        val w = LUMA_W
        val h = prev.size / w
        val pts = ArrayList<Pair<Float, Float>>()
        for (gy in 0 until GRID_Y) {
            for (gx in 0 until GRID_X) {
                val x = ((gx + 0.5f) * w / GRID_X).toInt().coerceIn(PATCH + 1, w - PATCH - 2)
                val y = ((gy + 0.5f) * h / GRID_Y).toInt().coerceIn(PATCH + 1, h - PATCH - 2)
                // ricerca best SSD
                var best = Int.MAX_VALUE
                var bx = 0
                var by = 0
                for (dy in -SEARCH..SEARCH step 2) {
                    for (dx in -SEARCH..SEARCH step 2) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx - PATCH < 0 || ny - PATCH < 0 || nx + PATCH >= w || ny + PATCH >= h) continue
                        var ssd = 0
                        for (py in -PATCH..PATCH) {
                            var po = (y + py) * w + x - PATCH
                            var no = (ny + py) * w + nx - PATCH
                            for (px2 in 0 until PATCH * 2 + 1) {
                                val a = prev[po + px2].toInt() and 0xFF
                                val b = cur[no + px2].toInt() and 0xFF
                                ssd += (a - b) * (a - b)
                            }
                        }
                        if (ssd < best) {
                            best = ssd
                            bx = dx
                            by = dy
                        }
                    }
                }
                if (best < 4000) { // soglia affidabilità
                    pts.add(x.toFloat() to y.toFloat())
                    pts.add((x + bx).toFloat() to (y + by).toFloat())
                }
            }
        }
        val n = pts.size / 2
        if (n < 12) return null

        // Horn closed-form: b = s·R·a + t (frame prev→cur).
        var ax = 0f
        var ay = 0f
        var bx = 0f
        var by = 0f
        for (i in 0 until n) {
            ax += pts[i * 2].first
            ay += pts[i * 2].second
            bx += pts[i * 2 + 1].first
            by += pts[i * 2 + 1].second
        }
        ax /= n; ay /= n; bx /= n; by /= n
        var sxx = 0f
        var sxy = 0f
        var syy = 0f
        var syx = 0f
        var norm = 0f
        for (i in 0 until n) {
            val px2 = pts[i * 2].first - ax
            val py2 = pts[i * 2].second - ay
            val qx = pts[i * 2 + 1].first - bx
            val qy = pts[i * 2 + 1].second - by
            sxx += px2 * qx
            sxy += px2 * qy
            syy += py2 * qy
            syx += py2 * qx
            norm += px2 * px2 + py2 * py2
        }
        if (norm < 1e-3f) return null
        val c = (sxx + syy) / norm
        val s = (sxy - syx) / norm
        val scale = sqrt(c * c + s * s)
        if (scale <= 0f || scale > 1.2f || scale < 0.83f) return null
        val angle = atan2(s, c)
        // traslazione: t = b̄ − s·R·ā
        val tx = bx - (c * ax - s * ay)
        val ty = by - (s * ax + c * ay)

        // filtro errori di riproiezione (una passata)
        var errSum = 0f
        for (i in 0 until n) {
            val px2 = pts[i * 2].first
            val py2 = pts[i * 2].second
            val ex = c * px2 - s * py2 + tx - pts[i * 2 + 1].first
            val ey = s * px2 + c * py2 + ty - pts[i * 2 + 1].second
            errSum += hypot(ex, ey)
        }
        if (errSum / n > 6f) return null

        return floatArrayOf(tx, ty, angle, kotlin.math.ln(scale))
    }

    // ------------------------------------------------------------ cache

    fun cacheFile(context: Context, key: String): File =
        File(File(context.filesDir, "stabilize").apply { mkdirs() }, "$key.json")

    fun saveCache(context: Context, key: String, res: Result) {
        runCatching {
            val arr = JSONArray()
            for (p in res.poses) {
                arr.put(
                    JSONObject()
                        .put("t", p.tMs).put("dx", p.dx.toDouble())
                        .put("dy", p.dy.toDouble()).put("a", p.angle.toDouble())
                        .put("s", p.scale.toDouble())
                )
            }
            cacheFile(context, key).writeText(
                JSONObject()
                    .put("poses", arr)
                    .put("cropZoom", res.cropZoom.toDouble())
                    .put("level", res.level)
                    .put("trimStartMs", res.trimStartMs)
                    .put("trimEndMs", res.trimEndMs)
                    .toString()
            )
        }
    }

    fun loadCache(context: Context, key: String, level: Int, trimStartMs: Long, trimEndMs: Long): Result? {
        return runCatching {
            val f = cacheFile(context, key)
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            if (o.optInt("level") != level ||
                o.optLong("trimStartMs") != trimStartMs || o.optLong("trimEndMs") != trimEndMs
            ) return null
            val arr = o.getJSONArray("poses")
            val poses = ArrayList<Pose>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                poses.add(
                    Pose(
                        p.getLong("t"), p.getDouble("dx").toFloat(),
                        p.getDouble("dy").toFloat(), p.getDouble("a").toFloat(),
                        p.getDouble("s").toFloat(),
                    )
                )
            }
            Result(poses, o.getDouble("cropZoom").toFloat(), level, trimStartMs, trimEndMs, 0)
        }.getOrNull()
    }
}

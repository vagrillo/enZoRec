package it.agrilloit.turegistri.ui.editor

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import it.agrilloit.turegistri.model.ColorGrade
import it.agrilloit.turegistri.model.Stabilizer

/**
 * Color grading come `RgbMatrix` media3: utilizzabile sia nell'export
 * (Transformer Effects) sia nell'anteprima (ExoPlayer.setVideoEffects).
 */
class ColorGradeMatrix(private val matrix: FloatArray) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, isHDR: Boolean): FloatArray = matrix
    override fun isNoOp(width: Int, height: Int): Boolean =
        ColorGrade.isIdentity(matrix)
}

/**
 * Zoom/pan animato del Vibe Director come `MatrixTransformation`: il fattore
 * di scala cresce da 1 a (1+strength) lungo la finestra della decisione,
 * centrato sul punto (cx, cy) normalizzato.
 */
@UnstableApi
class VibeZoomEffect(
    private val clipStartMs: Long,
    private val clipEndMs: Long,
    private val decisionStartMs: Long,
    private val decisionEndMs: Long,
    private val cx: Float,
    private val cy: Float,
    private val strength: Float,
) : MatrixTransformation {

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        val m = android.graphics.Matrix()
        val tMs = (presentationTimeUs / 1000) + clipStartMs
        if (tMs < decisionStartMs || tMs > decisionEndMs) return m
        val span = (decisionEndMs - decisionStartMs).coerceAtLeast(1)
        val progress = ((tMs - decisionStartMs).toFloat() / span).coerceIn(0f, 1f)
        // Ease-in-out
        val eased = if (progress < 0.5f) 2f * progress * progress else
            1f - 2f * (1f - progress) * (1f - progress)
        val z = 1f + strength * eased
        m.postScale(z, z, cx * 1080f, cy * 1928f)
        return m
    }
}

/** Effetto combinato per un clip da applicare all'export via media3. */
fun buildClipVideoEffectsForExport(
    clip: EditClip,
    vibeDecisions: List<it.agrilloit.turegistri.vibe.VibeDecision>,
): List<Effect> {
    val fx = mutableListOf<Effect>()
    if (clip.mirror) {
        fx.add(
            androidx.media3.effect.ScaleAndRotateTransformation.Builder()
                .setScale(-1f, 1f).build()
        )
    }
    if (kotlin.math.abs(clip.exposure) > 0.01f || kotlin.math.abs(clip.contrast - 1f) > 0.01f ||
        kotlin.math.abs(clip.saturation - 1f) > 0.01f || kotlin.math.abs(clip.warmth) > 0.01f
    ) {
        fx.add(
            ColorGradeMatrix(
                ColorGrade.applyControls(clip.exposure, clip.contrast, clip.saturation, clip.warmth)
            )
        )
    }
    for (d in vibeDecisions.filter { it.enabled && it.appliedToClipId == clip.id && it.type == "zoom" }) {
        fx.add(
            VibeZoomEffect(clip.trimStartMs, clip.trimEndMs, d.startMs, d.endMs, d.cx, d.cy, d.strength)
        )
    }
    return fx
}

/** Riferimento unico alla lista Effect per evitare import sparsi. */
typealias MediaEffect = Effect

/**
 * Effetto di stabilizzazione (port StabilizationPath iOS): per ogni frame
 * costruisce la matrice di campionamento inversa della pose stimata,
 * con crop zoom dai corner. La matrice mappa coordinate output → input.
 */
@UnstableApi
class StabilizeEffect(
    private val result: Stabilizer.Result,
    private val clipTrimStartMs: Long,
    private val clipSpeed: Float,
    private val clipDurMs: Long,
) : MatrixTransformation {

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
        val m = android.graphics.Matrix()
        // tempo sorgente corrispondente a questo frame dell'item
        val srcMs = clipTrimStartMs + ((presentationTimeUs / 1000) * clipSpeed).toLong()
        val pose = poseAt(srcMs) ?: return m
        val zoom = result.cropZoom

        m.postTranslate(-0.5f, -0.5f)
        m.postScale(1f / zoom, 1f / zoom)
        m.postScale(1f / pose.scale, 1f / pose.scale)
        m.postRotate(-Math.toDegrees(pose.angle.toDouble()).toFloat())
        m.postTranslate(-pose.dx, -pose.dy)
        m.postTranslate(0.5f, 0.5f)
        return m
    }

    private fun poseAt(srcMs: Long): Stabilizer.Pose? {
        val poses = result.poses
        if (poses.isEmpty()) return null
        if (srcMs <= poses.first().tMs) return poses.first()
        if (srcMs >= poses.last().tMs) return poses.last()
        var lo = 0
        var hi = poses.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (poses[mid].tMs <= srcMs) lo = mid else hi = mid
        }
        val a = poses[lo]
        val b = poses[hi]
        val f = ((srcMs - a.tMs).toFloat() / (b.tMs - a.tMs).coerceAtLeast(1)).coerceIn(0f, 1f)
        return Stabilizer.Pose(
            srcMs,
            a.dx + (b.dx - a.dx) * f,
            a.dy + (b.dy - a.dy) * f,
            a.angle + (b.angle - a.angle) * f,
            a.scale + (b.scale - a.scale) * f,
        )
    }
}

package it.agrilloit.turegistri.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextureOverlay
import it.agrilloit.turegistri.model.TitleDesign
import kotlin.math.abs

/**
 * Renderer del TitleDesign (Canvas, port di `StyledTextLayout` iOS) + overlay
 * media3 dinamico: la texture viene rigenerata solo quando cambia il frame
 * del motion (soglia 1/30 s) per non ricreare texture ogni frame.
 */
object TitleRenderer {

    /** Disegna il testo su bitmap ARGB trasparente. */
    fun render(design: TitleDesign, width: Int, height: Int, timelineMs: Long): Bitmap? {
        if (design.text.isBlank()) return null
        val window = (design.endMs - design.startMs).coerceAtLeast(1)
        val progress = ((timelineMs - design.startMs).toFloat() / window).coerceIn(0f, 1f)

        // Motion di entrata/uscita (entrata primi motionDurMs, uscita ultimi).
        val enterT = ((timelineMs - design.startMs).toFloat() / design.motionDurMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        val exitT = ((design.endMs - timelineMs).toFloat() / design.motionDurMs.coerceAtLeast(1)).coerceIn(0f, 1f)

        var alpha = 1f
        var scale = 1f
        var dxNorm = 0f
        when (design.motion) {
            "fade" -> alpha = minOf(enterT, exitT)
            "drift" -> {
                alpha = minOf(enterT, exitT)
                dxNorm = (1f - enterT) * -0.06f + (1f - exitT) * 0.06f
            }
            "scale" -> {
                alpha = minOf(enterT, exitT)
                scale = 0.9f + 0.1f * minOf(enterT, exitT)
            }
        }
        if (alpha <= 0.01f) return null

        val px = (width * (design.posX + dxNorm)).toInt()
        val py = (height * design.posY).toInt()
        val textSizePx = design.fontSizeSp * (height / 1280f) * scale

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = design.typeface()
            textSize = textSizePx
            textAlign = Paint.Align.CENTER
            if (design.gradient) {
                val ang = Math.toRadians(design.gradientAngleDeg.toDouble())
                val r = textSizePx * 4
                shader = LinearGradient(
                    px - (r * kotlin.math.cos(ang)).toFloat(),
                    py - (r * kotlin.math.sin(ang)).toFloat(),
                    px + (r * kotlin.math.cos(ang)).toFloat(),
                    py + (r * kotlin.math.sin(ang)).toFloat(),
                    design.colorStart, design.colorEnd, Shader.TileMode.CLAMP,
                )
            } else {
                color = design.colorStart
            }
        }

        val lines = design.text.split("\n")
        val lineH = textSizePx * 1.25f
        val totalH = lineH * lines.size
        val fm = paint.fontMetrics

        // Misura per il plate.
        var maxW = 0f
        for (l in lines) maxW = maxOf(maxW, paint.measureText(l))

        val bm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bm)

        // Plate (block = un rettangolo per tutto, perLine = uno per riga).
        if (design.plate != "none") {
            val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = design.plateColor }
            if (design.plate == "block") {
                val left = px - maxW / 2 - design.platePaddingPx
                val top = py - totalH / 2 - design.platePaddingPx + textSizePx * 0.6f
                canvas.drawRoundRect(
                    left, top, left + maxW + design.platePaddingPx * 2,
                    top + totalH + design.platePaddingPx, 18f, 18f, platePaint,
                )
            } else {
                for ((i, l) in lines.withIndex()) {
                    val lw = paint.measureText(l)
                    val cy = py - totalH / 2 + lineH * i + lineH / 2 + (fm.descent + fm.ascent) / 2
                    canvas.drawRoundRect(
                        px - lw / 2 - design.platePaddingPx,
                        cy - lineH / 2 - 4,
                        px + lw / 2 + design.platePaddingPx,
                        cy + lineH / 2 + 4,
                        12f, 12f, platePaint,
                    )
                }
            }
        }

        // Shadow pass.
        if (design.shadowBlurPx > 0f) {
            val shadowPaint = Paint(paint)
            shadowPaint.shader = null
            shadowPaint.color = design.shadowColor
            shadowPaint.setShadowLayer(design.shadowBlurPx, 0f, design.shadowOffsetY, design.shadowColor)
            for ((i, l) in lines.withIndex()) {
                val cy = py - totalH / 2 + lineH * i + lineH / 2 + (fm.descent + fm.ascent) / 2
                canvas.drawText(l, px.toFloat(), cy, shadowPaint)
            }
        }

        // Outline pass.
        if (design.outlineWidthPx > 0f) {
            val outlinePaint = Paint(paint)
            outlinePaint.shader = null
            outlinePaint.style = Paint.Style.STROKE
            outlinePaint.strokeWidth = design.outlineWidthPx
            outlinePaint.color = design.outlineColor
            for ((i, l) in lines.withIndex()) {
                val cy = py - totalH / 2 + lineH * i + lineH / 2 + (fm.descent + fm.ascent) / 2
                canvas.drawText(l, px.toFloat(), cy, outlinePaint)
            }
        }

        // Fill pass (con alpha motion).
        paint.alpha = (alpha * 255).toInt()
        for ((i, l) in lines.withIndex()) {
            val cy = py - totalH / 2 + lineH * i + lineH / 2 + (fm.descent + fm.ascent) / 2
            canvas.drawText(l, px.toFloat(), cy, paint)
        }
        return bm
    }
}

/**
 * Overlay media3 per il titolo (texture dinamica con cache per step di tempo).
 */
@UnstableApi
class TitleOverlay(
    private val design: TitleDesign,
    /** Inizio dell'item export sulla timeline globale (per il motion della finestra). */
    private val itemStartMs: Long = 0,
) : TextureOverlay() {

    private var cachedKey = -1L
    private var cachedTex = -1
    private var blankTex = -1
    private var cachedSize: Size? = null

    private fun blankTexture(): Int {
        if (blankTex < 0) {
            val bm = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            bm.eraseColor(0)
            blankTex = GlUtil.createTexture(bm)
        }
        return blankTex
    }

    override fun getTextureId(presentationTimeUs: Long): Int {
        val tMs = itemStartMs + presentationTimeUs / 1000
        // Cache per CONTENUTO: senza motion il bitmap è costante (texture creata
        // una volta); con motion rigenera solo nei bucket da 33ms del movimento.
        val key: Long = if (design.motion == "none") {
            (design.text.hashCode() * 31 + design.fontSizeSp.toInt()).toLong()
        } else {
            (design.text.hashCode() * 31 + design.fontSizeSp.toInt()).toLong() * 1000 +
                (tMs / 33).coerceIn(0, 999)
        }
        if (key != cachedKey) {
            if (cachedTex >= 0) {
                runCatching { GlUtil.deleteTexture(cachedTex) }
                cachedTex = -1
            }
            val size = cachedSize ?: Size(1080, 1920)
            val bmp = TitleRenderer.render(design, size.width, size.height, tMs)
            if (bmp != null) {
                cachedTex = GlUtil.createTexture(bmp)
            }
            cachedKey = key
        }
        return if (cachedTex >= 0) cachedTex else blankTexture()
    }

    override fun getTextureSize(presentationTimeUs: Long): Size =
        if (cachedTex >= 0) (cachedSize ?: Size(1080, 1920)) else Size(1, 1)

    override fun configure(frameSize: Size) {
        cachedSize = frameSize
        super.configure(frameSize)
    }

    override fun release() {
        if (cachedTex >= 0) {
            runCatching { GlUtil.deleteTexture(cachedTex) }
            cachedTex = -1
        }
        if (blankTex >= 0) {
            runCatching { GlUtil.deleteTexture(blankTex) }
            blankTex = -1
        }
        super.release()
    }
}

/** Design dell'overlay immagine (foto/logo PNG trasparente) proiettolasullo timeline. */
data class ImageOverlayDesign(
    val path: String,
    val startMs: Long = 0,
    val endMs: Long = 3000,
    val posX: Float = 0.85f,
    val posY: Float = 0.12f,
    /** Frazione della larghezza frame. */
    val scale: Float = 0.2f,
    val alpha: Float = 1f,
)

/** Overlay immagine statica (texture caricata una volta). */
@UnstableApi
class ImageOverlay(
    private val design: ImageOverlayDesign,
    private val itemStartMs: Long = 0,
) : TextureOverlay() {

    private var tex = -1
    private var bmp: Bitmap? = null
    private var blankTex = -1

    private fun blankTexture(): Int {
        if (blankTex < 0) {
            val bm = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            bm.eraseColor(0)
            blankTex = GlUtil.createTexture(bm)
        }
        return blankTex
    }

    private fun bitmap(): Bitmap? {
        if (bmp == null) {
            val loaded = android.graphics.BitmapFactory.decodeFile(design.path)
            if (loaded != null) {
                val b = Bitmap.createBitmap(loaded, 0, 0, loaded.width, loaded.height)
                if (loaded != b) loaded.recycle()
                bmp = b
            }
        }
        return bmp
    }

    override fun getTextureId(presentationTimeUs: Long): Int {
        val tMs = itemStartMs + presentationTimeUs / 1000
        return if (tMs in design.startMs..design.endMs) {
            if (tex < 0) {
                bitmap()?.let { tex = GlUtil.createTexture(it) }
            }
            if (tex >= 0) tex else blankTexture()
        } else {
            blankTexture()
        }
    }

    override fun getTextureSize(presentationTimeUs: Long): Size {
        val tMs = itemStartMs + presentationTimeUs / 1000
        val b = bitmap()
        return if (tMs in design.startMs..design.endMs && b != null) {
            Size(b.width, b.height)
        } else {
            Size(1, 1)
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val tMs = itemStartMs + presentationTimeUs / 1000
        val scale = if (tMs in design.startMs..design.endMs) design.scale else 0.0001f
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(design.posX, design.posY)
            .setOverlayFrameAnchor(0.5f, 0.5f)
            .setScale(scale, scale)
            .setAlphaScale(if (tMs in design.startMs..design.endMs) design.alpha else 0f)
            .build()
    }

    override fun release() {
        if (tex >= 0) runCatching { GlUtil.deleteTexture(tex) }
        if (blankTex >= 0) runCatching { GlUtil.deleteTexture(blankTex) }
        tex = -1
        blankTex = -1
        super.release()
    }
}

/** Overlay sottotitoli: renderizza la cue attiva alla timeline globale. */
@UnstableApi
class SubtitlesOverlay(
    private val style: it.agrilloit.turegistri.subtitles.SubtitleStyle,
    private val cues: List<it.agrilloit.turegistri.subtitles.Cue>,
    private val itemStartMs: Long = 0,
) : TextureOverlay() {

    private var cachedKey = -1L
    private var cachedTex = -1
    private var blankTex = -1
    private var cachedSize: Size? = null

    private fun blankTexture(): Int {
        if (blankTex < 0) {
            val bm = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            bm.eraseColor(0)
            blankTex = GlUtil.createTexture(bm)
        }
        return blankTex
    }

    override fun getTextureId(presentationTimeUs: Long): Int {
        val tMs = itemStartMs + presentationTimeUs / 1000
        // Cache per cue attiva (il bitmap cambia solo al cambio di cue).
        val cue = cues.firstOrNull { tMs >= it.startMs && tMs <= it.endMs }
        val key: Long = cue?.let { (it.id.hashCode().toLong() shl 16) + it.text.length } ?: -1L
        if (key != cachedKey) {
            if (cachedTex >= 0) {
                runCatching { GlUtil.deleteTexture(cachedTex) }
                cachedTex = -1
            }
            val size = cachedSize ?: Size(1080, 1920)
            if (cue != null) {
                val design = style.toTitleDesign(cue.text, cue.startMs, cue.endMs)
                val bmp = TitleRenderer.render(design, size.width, size.height, tMs)
                if (bmp != null) cachedTex = GlUtil.createTexture(bmp)
            }
            cachedKey = key
        }
        return if (cachedTex >= 0) cachedTex else blankTexture()
    }

    override fun getTextureSize(presentationTimeUs: Long): Size =
        if (cachedTex >= 0) (cachedSize ?: Size(1080, 1920)) else Size(1, 1)

    override fun configure(frameSize: Size) {
        cachedSize = frameSize
        super.configure(frameSize)
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0.5f, style.posY)
            .setOverlayFrameAnchor(0.5f, 0.5f)
            .build()

    override fun release() {
        if (cachedTex >= 0) runCatching { GlUtil.deleteTexture(cachedTex) }
        if (blankTex >= 0) runCatching { GlUtil.deleteTexture(blankTex) }
        cachedTex = -1
        blankTex = -1
        super.release()
    }
}

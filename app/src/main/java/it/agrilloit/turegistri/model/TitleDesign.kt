package it.agrilloit.turegistri.model

/**
 * Title designer (port di `TitleDesign` iOS): tipografia, gradiente, outline,
 * shadow, plate, 8 preset look e motion di entrata/uscita. Il rendering è un
 * Canvas Android (equivalente del CoreText rasterizer iOS) condiviso da
 * overlay export e anteprima.
 */
data class TitleDesign(
    val text: String = "",
    /** 8 preset look. */
    val preset: String = "clean",
    /** Font id: system | modern | classic | mono | narrow | script | black */
    val fontId: String = "system",
    val fontSizeSp: Float = 32f,
    /** Colori fill o gradiente 2-stop (argb). */
    val colorStart: Int = 0xFFFFFFFF.toInt(),
    val colorEnd: Int = 0xFFFFFFFF.toInt(),
    val gradient: Boolean = false,
    val gradientAngleDeg: Float = 90f,
    val outlineColor: Int = 0xFF000000.toInt(),
    val outlineWidthPx: Float = 0f,
    val shadowColor: Int = 0x80000000.toInt(),
    val shadowBlurPx: Float = 0f,
    val shadowOffsetY: Float = 0f,
    /** Plate dietro al testo: none | block | perLine */
    val plate: String = "none",
    val plateColor: Int = 0xAA000000.toInt(),
    val platePaddingPx: Float = 12f,
    /** Motion: none | fade | drift | scale */
    val motion: String = "none",
    val motionDurMs: Long = 400,
    /** Finestra temporale sulla timeline export. */
    val startMs: Long = 0,
    val endMs: Long = 3000,
    /** Posizione normalizzata (0..1, ancoraggio centro). */
    val posX: Float = 0.5f,
    val posY: Float = 0.82f,
) {
    val presetLabel: String
        get() = when (preset) {
            "filmTitle" -> "Film title"
            "lowerThird" -> "Lower third"
            "headline" -> "Headline"
            "fieldNote" -> "Field note"
            "dateline" -> "Dateline"
            "warmEditorial" -> "Warm editorial"
            "sunsetMono" -> "Sunset mono"
            else -> "Clean"
        }

    /** Applica il preset (stessa palette di iOS). */
    fun withPreset(p: String): TitleDesign = when (p) {
        "filmTitle" -> copy(
            preset = p, fontId = "classic", fontSizeSp = 44f, gradient = true,
            colorStart = 0xFFF5E6C8.toInt(), colorEnd = 0xFFC8A060.toInt(),
            outlineWidthPx = 2f, shadowBlurPx = 18f, motion = "fade", plate = "none",
            posY = 0.5f,
        )

        "lowerThird" -> copy(
            preset = p, fontId = "modern", fontSizeSp = 26f, gradient = false,
            colorStart = 0xFFFFFFFF.toInt(), plate = "block",
            plateColor = 0xB3000000.toInt(), motion = "drift", posY = 0.86f,
        )

        "headline" -> copy(
            preset = p, fontId = "black", fontSizeSp = 40f, gradient = false,
            colorStart = 0xFFFFFFFF.toInt(), outlineWidthPx = 3f, motion = "scale",
            posY = 0.4f,
        )

        "fieldNote" -> copy(
            preset = p, fontId = "mono", fontSizeSp = 22f, gradient = false,
            colorStart = 0xFFFFF8E7.toInt(), shadowBlurPx = 6f, motion = "none",
            posY = 0.9f,
        )

        "dateline" -> copy(
            preset = p, fontId = "narrow", fontSizeSp = 24f, gradient = false,
            colorStart = 0xFF64D2FF.toInt(), plate = "perLine", motion = "drift",
            posY = 0.12f,
        )

        "warmEditorial" -> copy(
            preset = p, fontId = "script", fontSizeSp = 36f, gradient = true,
            colorStart = 0xFFFFD9A0.toInt(), colorEnd = 0xFFFF8A65.toInt(),
            shadowBlurPx = 10f, motion = "fade",
        )

        "sunsetMono" -> copy(
            preset = p, fontId = "modern", fontSizeSp = 30f, gradient = true,
            colorStart = 0xFFFFB347.toInt(), colorEnd = 0xFFFF3B6B.toInt(),
            motion = "none", posY = 0.6f,
        )

        else -> copy(preset = "clean", fontId = "system", fontSizeSp = 32f,
            gradient = false, colorStart = 0xFFFFFFFF.toInt(),
            outlineWidthPx = 0f, shadowBlurPx = 8f, motion = "none")
    }

    /** Typeface per fontId (sistema Android). */
    fun typeface(): android.graphics.Typeface = when (fontId) {
        "modern" -> android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        "classic" -> android.graphics.Typeface.SERIF
        "mono" -> android.graphics.Typeface.MONOSPACE
        "narrow" -> android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
        "script" -> android.graphics.Typeface.SERIF
        "black" -> android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)
        else -> android.graphics.Typeface.DEFAULT_BOLD
    }
}

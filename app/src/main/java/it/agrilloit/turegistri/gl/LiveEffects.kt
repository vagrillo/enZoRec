package it.agrilloit.turegistri.gl

/**
 * Effetti live del registratore (port LiveVideoEffects iOS — subset live-capable
 * del catalogo): applicati nel fragment shader del CompositeRenderer in anteprima
 * E dentro il composito registrato (uniform per lane).
 */
data class LiveEffect(
    val mode: Int,
    val p1: Float = 0f,
    val p2: Float = 1f,
    val p3: Float = 0f,
    val p4: Float = 0f,
)

object LiveEffectCatalog {

    /** voci: id, label, mode, p1..p4 di default. */
    data class Entry(
        val id: String,
        val label: String,
        val mode: Int,
        val p1: Float = 0f,
        val p2: Float = 1f,
        val p3: Float = 0f,
        val p4: Float = 0f,
        /** parametri regolabili con lo slider "intensità" (mappa su p2). */
        val strengthAdjustable: Boolean = true,
    )

    val NONE = Entry("none", "Nessuno", 0)

    val ENTRIES = listOf(
        NONE,
        Entry("silentTint", "Silent Tint", 1, p1 = 1f, p2 = 0.8f),
        Entry("noir", "Noir", 2, p2 = 1f),
        Entry("bleachBypass", "Bleach Bypass", 3, p2 = 1f),
        Entry("dayForNight", "Day for Night", 4, p2 = 1f),
        Entry("quantization", "Quantizzazione", 5, p1 = 5f, p2 = 1f),
        Entry("colorIsolation", "Isolamento colore", 6, p1 = 0f, p2 = 1f, p3 = 40f),
        Entry("kaleidoscope", "Caleidoscopio", 7, p1 = 6f, p2 = 1f, p3 = 0.5f, p4 = 0.5f),
        Entry("rgbOffset", "RGB Offset", 8, p1 = 1f, p2 = 1f, p3 = 0f),
        Entry("negativeStrobe", "Negative Strobe", 9, p1 = 6f, p2 = 1f),
        Entry("widescreen", "Widescreen 2.39", 10, p1 = 2.39f, p2 = 1f),
    )

    fun byId(id: String): Entry? = ENTRIES.firstOrNull { it.id == id }

    /** Intensità 0..1 → LiveEffect con i parametri scalati. */
    fun build(entry: Entry, strength: Float): LiveEffect =
        LiveEffect(
            mode = entry.mode,
            p1 = entry.p1,
            p2 = if (entry.strengthAdjustable) strength else entry.p2,
            p3 = entry.p3,
            p4 = entry.p4,
        )
}

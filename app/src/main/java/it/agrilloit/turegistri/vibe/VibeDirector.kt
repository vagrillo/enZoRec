package it.agrilloit.turegistri.vibe

import it.agrilloit.turegistri.ui.editor.EditClip

/**
 * "Vibe Director" — regista automatico ispirato a TuDirector:
 * analizza energia audio e ritmo dei take, poi genera **decisioni di regia
 * tipizzate** (zoom, pan colore, switch camera, tagli) secondo una **skill**
 * (vibe) scelta. Ogni decisione ha un "perché" ed è disattivabile.
 *
 * v1: regista locale euristico (nessuna API key). Le idee LLM/vision di
 * TuDirector (trascrizione, vision frame-by-frame, finestre 30s) sono
 * progettate per una fase 2 con OpenRouter.
 */

enum class VibeSkill(val id: String, val label: String, val description: String) {
    INTERVISTA(
        "intervista", "Intervista / speaker fermo",
        "Zoom morbidi sui momenti chiave, tagli posati, nessun effetto wild.",
    ),
    CINEMATICO(
        "cinematico", "Cinematico",
        "Ritmo lento, movimenti lunghi, colore freddo/desaturato, tagli sui silenzi.",
    ),
    VLOG_DINAMICO(
        "vlog", "Vlog dinamico",
        "Tagli frequenti sugli aumenti di energia, zoom decisi, saturazione viva.",
    ),
    EVENTO(
        "evento", "Evento / azione",
        "Switch rapidi tra camere, zoom brevi e decisi sui picchi di energia.",
    ),
}

/** Una decisione di regia tipizzata (una sola per riga, editabile). */
data class VibeDecision(
    val id: String,
    val type: String, // "zoom" | "color" | "cut" | "speed" | "volume" | "marker" | "camera_switch"
    val startMs: Long, // sulla timeline modificata
    val endMs: Long,
    /** clip di destinazione (per zoom/color applicati per clip). */
    val appliedToClipId: String,
    /** Parametri tipizzati del tool (clampati dal VibeTools registry). */
    val params: Map<String, Double> = emptyMap(),
    val cx: Float = 0.5f,
    val cy: Float = 0.45f,
    val strength: Float = 0.3f,
    val enabled: Boolean = true,
    val why: String,
)

/**
 * Analisi euristica offline: energia dai waveform peaks + ritmo.
 * Stessa filosofia di TuDirector (finestre 30 s, decisioni tipizzate) ma
 * senza LLM: le "finestre" sono i momenti di alta/bassa energia.
 */
object VibeDirector {

    data class EnergyPoint(val startMs: Long, val endMs: Long, val energy: Float)

    /**
     * Genera le decisioni per la skill data.
     * @param waveforms waveform per clip (peaks 0..1) già allineate.
     */
    fun direct(
        clips: List<it.agrilloit.turegistri.ui.editor.EditClip>,
        waveforms: Map<String, List<Float>>,
        skill: String,
        prompt: String,
    ): List<VibeDecision> {
        val decisions = mutableListOf<VibeDecision>()
        val skillSpec = skillsFor(skill, prompt)
        var globalMs = 0L
        var idSeq = 0

        for (clip in clips) {
            val peaks = waveforms[clip.id] ?: emptyList()
            val windows = energyWindows(peaks, clip.effectiveMs())
            var localMs = 0L
            var lastDecisionEnd = -1L
            var decisionCount = 0

            for (window in windows) {
                val winStart = globalMs + window.startMs
                val winEnd = globalMs + window.endMs
                val high = window.energy >= skillSpec.energyThreshold

                if (high && skillSpec.zoomOnEnergy && decisionCount < skillSpec.maxDecisions) {
                    if (winStart - lastDecisionEnd >= skillSpec.minGapMs) {
                        val strength = (0.2f + window.energy * skillSpec.zoomGain)
                            .coerceIn(0.15f, skillSpec.maxZoomStrength)
                        val clamped = VibeTools.ZOOM.clamp(
                            mapOf("cx" to 0.5, "cy" to 0.42, "strength" to strength.toDouble()),
                            (winEnd - winStart),
                        )
                        decisions.add(
                            VibeDecision(
                                id = "z${idSeq++}",
                                type = "zoom",
                                startMs = winStart,
                                endMs = winEnd,
                                appliedToClipId = clip.id,
                                params = clamped,
                                cx = clamped["cx"]?.toFloat() ?: 0.5f,
                                cy = clamped["cy"]?.toFloat() ?: 0.45f,
                                strength = clamped["strength"]?.toFloat() ?: 0.3f,
                                why = "Picco di energia (${(window.energy * 100).toInt()}%): " +
                                    skillSpec.zoomWhy,
                            )
                        )
                        lastDecisionEnd = winEnd
                        decisionCount++
                    }
                }

                if (!high && skillSpec.colorOnCalm) {
                    decisions.add(
                        VibeDecision(
                            id = "c${idSeq++}",
                            type = "color",
                            startMs = winStart,
                            endMs = winEnd,
                            appliedToClipId = clip.id,
                            params = mapOf("strength" to 0.25),
                            strength = 0.25f,
                            why = skillSpec.calmWhy,
                        )
                    )
                }
                localMs = window.endMs
            }
            globalMs += clip.effectiveMs()
        }
        return decisions
    }

    private data class Window(val startMs: Long, val endMs: Long, val energy: Float)

    /** Divide il clip in finestre da ~5 s e ne calcola l'energia media. */
    private fun energyWindows(peaks: List<Float>, clipMs: Long): List<EnergyPoint> {
        if (peaks.isEmpty() || clipMs <= 0) return emptyList()
        val out = mutableListOf<EnergyPoint>()
        val windowMs = 5000L
        var t = 0L
        while (t < clipMs) {
            val end = minOf(t + windowMs, clipMs)
            val from = (t.toDouble() / clipMs * peaks.size).toInt()
            val to = (end.toDouble() / clipMs * peaks.size).toInt().coerceAtLeast(from + 1)
            var acc = 0f
            for (i in from until minOf(to, peaks.size)) acc += peaks[i]
            val energy = (acc / maxOf(1, to - from)).coerceIn(0f, 1f)
            out.add(EnergyPoint(t, end, energy))
            t = end
        }
        return out
    }

    private data class SkillSpec(
        val energyThreshold: Float,
        val zoomOnEnergy: Boolean,
        val zoomGain: Float,
        val maxZoomStrength: Float,
        val maxDecisions: Int,
        val minGapMs: Long,
        val colorOnCalm: Boolean,
        val calmWhy: String,
        val zoomWhy: String,
    )

    private fun skillsFor(skill: String, prompt: String): SkillSpec = when (skill) {
        "cinematico" -> SkillSpec(
            energyThreshold = 0.62f, zoomOnEnergy = false, zoomGain = 0f,
            maxZoomStrength = 0.25f, maxDecisions = 3, minGapMs = 6000,
            colorOnCalm = true, calmWhy = "Momento calmo: desaturazione cinematografica",
            zoomWhy = "Zoom lento sul momento chiave",
        )

        "vlog" -> SkillSpec(
            energyThreshold = 0.42f, zoomOnEnergy = true, zoomGain = 1.2f,
            maxZoomStrength = 0.45f, maxDecisions = 8, minGapMs = 2500,
            colorOnCalm = false, calmWhy = "", zoomWhy = "Picco di energia: zoom deciso",
        )

        "evento" -> SkillSpec(
            energyThreshold = 0.5f, zoomOnEnergy = true, zoomGain = 0.9f,
            maxZoomStrength = 0.4f, maxDecisions = 6, minGapMs = 1800,
            colorOnCalm = false, calmWhy = "", zoomWhy = "Azione: zoom breve e deciso",
        )

        else -> SkillSpec(
            energyThreshold = 0.55f, zoomOnEnergy = true, zoomGain = 0.6f,
            maxZoomStrength = 0.3f, maxDecisions = 5, minGapMs = 4000,
            colorOnCalm = true,
            calmWhy = "Momento posato: colore sobrio",
            zoomWhy = "Primo piano sul momento importante",
        )
    }
}

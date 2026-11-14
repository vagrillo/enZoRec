package it.agrilloit.turegistri.audio

/**
 * Rilevatore di pause (port di `PauseDetector` iOS): soglia automatica dai
 * percentili dell'energia (room tone), durata minima e padding indipendente
 * prima/dopo. I tagli reali sono applicati dal ViewModel a livello di clip
 * (sorgente, non distruttivo).
 */
object PauseDetector {

    data class PauseWindow(val startMs: Long, val endMs: Long)

    data class Params(
        val minSilenceMs: Long = 700,
        val paddingBeforeMs: Long = 120,
        val paddingAfterMs: Long = 150,
        /** Override manuale della soglia in dBFS (null = automatica). */
        val thresholdDb: Float? = null,
    )

    /**
     * @param peaks energia 0..1 campionata uniformemente sull'intero clip.
     * @param clipMs durata del clip (timeline effettiva, col suo speed).
     */
    fun detect(peaks: List<Float>, clipMs: Long, params: Params = Params()): List<PauseWindow> {
        if (peaks.isEmpty() || clipMs <= 0) return emptyList()
        val msPerPeak = clipMs.toDouble() / peaks.size
        val sorted = peaks.sorted()
        fun percentile(p: Float): Float =
            sorted[((p * (sorted.size - 1)).toInt()).coerceIn(0, sorted.size - 1)]

        val threshold = params.thresholdDb?.let { Math.pow(10.0, it / 20.0).toFloat() }
            ?: maxOf(0.02f, percentile(0.2f) * 3f)

        val windows = ArrayList<PauseWindow>()
        var runStart = -1
        for ((i, p) in peaks.withIndex()) {
            val silent = p < threshold
            val tMs = (i * msPerPeak).toLong()
            if (silent && runStart < 0) runStart = i
            if (!silent && runStart >= 0) {
                val dur = ((i - runStart) * msPerPeak).toLong()
                if (dur >= params.minSilenceMs) {
                    windows.add(PauseWindow((runStart * msPerPeak).toLong(), tMs))
                }
                runStart = -1
            }
        }
        if (runStart >= 0) {
            val dur = ((peaks.size - runStart) * msPerPeak).toLong()
            if (dur >= params.minSilenceMs) {
                windows.add(PauseWindow((runStart * msPerPeak).toLong(), clipMs))
            }
        }

        return windows.mapNotNull { w ->
            val s = (w.startMs - params.paddingBeforeMs).coerceAtLeast(0)
            val e = (w.endMs + params.paddingAfterMs).coerceAtMost(clipMs)
            if (e - s < 300) return@mapNotNull null
            // guardia: non cancellare tutto il clip
            if (s < 800 && e >= clipMs - 800) return@mapNotNull null
            PauseWindow(s, e)
        }
    }
}

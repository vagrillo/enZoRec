package it.agrilloit.turegistri.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Motore di layout puro (testabile in JVM): data la modalità, la frazione di split,
 * la posizione/dimensione dell'inserto PiP e gli aspect ratio dei flussi camera
 * (già ruotati in orientamento di visualizzazione), calcola le regioni normalizzate
 * dello schermo e i ritagli di testo (center-crop) per ogni camera.
 *
 * Tutte le coordinate sono frazioni 0..1 relative al viewport di destinazione.
 */
object LayoutEngine {

    /** Regione rettangolare normalizzata: origine in alto-sinistra. */
    data class Region(val x: Float, val y: Float, val w: Float, val h: Float) {
        val x2: Float get() = x + w
        val y2: Float get() = y + h
    }

    /**
     * Ritaglio uv (center-crop) nel piano del flusso già ruotato:
     * (u0, v0) angolo alto-sinistra, (u1, v1) angolo basso-destra.
     */
    data class Crop(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
        companion object {
            val FULL = Crop(0f, 0f, 1f, 1f)
        }
    }

    data class Placement(
        val cameraIndex: Int,
        val region: Region,
        val crop: Crop,
    )

    data class LayoutState(
        val mode: LayoutMode = LayoutMode.SPLIT_V,
        /** Frazione di split per SPLIT_V/SPLIT_H (porzione della camera A), 0.1..0.9. */
        val splitFraction: Float = 0.5f,
        /** Centro dell'inserto PiP, frazioni del viewport. */
        val pipCenterX: Float = 0.5f,
        val pipCenterY: Float = 0.5f,
        /** Larghezza dell'inserto PiP come frazione della larghezza del viewport. */
        val pipWidth: Float = 0.6f,
        /**
         * true = adatta il fotogramma intero nel pannello (bande nere, nessun crop);
         * false = riempi il pannello con center-crop.
         */
        val fitFrame: Boolean = true,
        /** Scambio delle lane: camera B al posto della A e viceversa (anche in registrazione). */
        val swapped: Boolean = false,
    )

    /**
     * @param streamAspects aspect ratio (w/h) di ogni flusso camera nell'orientamento
     *        di visualizzazione (per un buffer 1280x720 ruotato di 90° => 720/1280).
     * @param viewportAspect aspect ratio (w/h) del viewport di destinazione.
     */
    fun compute(
        state: LayoutState,
        streamAspects: List<Float>,
        viewportAspect: Float,
        cameraCount: Int,
    ): List<Placement> {
        if (cameraCount <= 0) return emptyList()
        // Con lane scambiate, A usa l'aspect della camera B e viceversa.
        val effectiveAspects = if (state.swapped && streamAspects.size > 1) {
            listOf(streamAspects[1], streamAspects[0]) + streamAspects.drop(2)
        } else {
            streamAspects
        }
        val aspectA = effectiveAspects.getOrElse(0) { 9f / 16f }
        val aspectB = effectiveAspects.getOrElse(1) { aspectA }

        fun place(index: Int, aspect: Float, region: Region): Placement {
            return if (state.fitFrame) {
                // Fotogramma intero dentro la regione (letterbox), nessun crop.
                Placement(index, fitInside(region, aspect, viewportAspect), Crop.FULL)
            } else {
                val regionAspect = (region.w * viewportAspect) / region.h
                Placement(index, region, coverCrop(aspect, regionAspect))
            }
        }

        val result: List<Placement> = when (state.mode) {
            LayoutMode.SINGLE_A -> listOf(
                place(0, aspectA, Region(0f, 0f, 1f, 1f)),
            )

            LayoutMode.SINGLE_B -> if (cameraCount > 1) listOf(
                place(1, aspectB, Region(0f, 0f, 1f, 1f)),
            ) else listOf(
                place(0, aspectA, Region(0f, 0f, 1f, 1f)),
            )

            LayoutMode.SPLIT_V -> {
                if (cameraCount < 2) return applySwap(state, cameraCount, listOf(place(0, aspectA, Region(0f, 0f, 1f, 1f))))
                val f = state.splitFraction.coerceIn(0.1f, 0.9f)
                listOf(
                    place(0, aspectA, Region(0f, 0f, f, 1f)),
                    place(1, aspectB, Region(f, 0f, 1f - f, 1f)),
                )
            }

            LayoutMode.SPLIT_H -> {
                if (cameraCount < 2) return applySwap(state, cameraCount, listOf(place(0, aspectA, Region(0f, 0f, 1f, 1f))))
                val f = state.splitFraction.coerceIn(0.1f, 0.9f)
                listOf(
                    place(0, aspectA, Region(0f, 0f, 1f, f)),
                    place(1, aspectB, Region(0f, f, 1f, 1f - f)),
                )
            }

            LayoutMode.PIP -> {
                if (cameraCount < 2) return applySwap(state, cameraCount, listOf(place(0, aspectA, Region(0f, 0f, 1f, 1f))))
                val background = place(0, aspectA, Region(0f, 0f, 1f, 1f))
                // Inserto senza crop: mostra il fotogramma completo della camera B.
                val w = state.pipWidth.coerceIn(0.2f, 0.95f)
                val h = w * viewportAspect / aspectB
                val cx = state.pipCenterX.coerceIn(w / 2f, 1f - w / 2f)
                val cy = state.pipCenterY.coerceIn(h / 2f, 1f - h / 2f)
                val inset = Placement(
                    1,
                    Region(cx - w / 2f, cy - h / 2f, w, h),
                    Crop.FULL,
                )
                listOf(background, inset)
            }
        }
        return applySwap(state, cameraCount, result)
        // Applica lo scambio delle lane a tutti i placement.
        return if (state.swapped && cameraCount > 1) {
            result.map { it.copy(cameraIndex = 1 - it.cameraIndex) }
        } else {
            result
        }
    }

    /** Applica lo scambio delle lane ai placement già calcolati. */
    private fun applySwap(state: LayoutState, cameraCount: Int, placements: List<Placement>): List<Placement> =
        if (state.swapped && cameraCount > 1 && placements.size > 1) {
            placements.map { it.copy(cameraIndex = 1 - it.cameraIndex) }
        } else {
            placements
        }

    /**
     * Rimpicciolisce la regione fino a contenere TUTTO il fotogramma (letterbox),
     * mantenendo il centro della regione originale.
     */
    fun fitInside(region: Region, streamAspect: Float, viewportAspect: Float): Region {
        if (streamAspect <= 0f || viewportAspect <= 0f) return region
        val regionAspect = (region.w * viewportAspect) / region.h
        var w = region.w
        var h = region.h
        if (streamAspect > regionAspect) {
            // Il flusso è più largo: fit in larghezza, altezza ridotta.
            h = w * viewportAspect / streamAspect
        } else {
            // Il flusso è più alto: fit in altezza, larghezza ridotta.
            w = h * streamAspect / viewportAspect
        }
        val cx = region.x + region.w / 2f
        val cy = region.y + region.h / 2f
        return Region(cx - w / 2f, cy - h / 2f, w, h)
    }

    /**
     * Center-crop: il flusso riempie tutta la regione, l'eccesso viene tagliato.
     */
    fun coverCrop(streamAspect: Float, regionAspect: Float): Crop {
        if (streamAspect <= 0f || regionAspect <= 0f || streamAspect.isNaN() || regionAspect.isNaN()) {
            return Crop.FULL
        }
        return if (abs(streamAspect - regionAspect) < 1e-4f) {
            Crop.FULL
        } else if (streamAspect > regionAspect) {
            // Flusso più largo della regione: taglio ai lati.
            val visibleW = regionAspect / streamAspect
            val margin = (1f - visibleW) / 2f
            Crop(margin, 0f, 1f - margin, 1f)
        } else {
            // Flusso più alto della regione: taglio sopra e sotto.
            val visibleH = streamAspect / regionAspect
            val margin = (1f - visibleH) / 2f
            Crop(0f, margin, 1f, 1f - margin)
        }
    }

    /** Utility per il tocco: sposta la frazione di split della variazione di drag. */
    fun dragSplit(state: LayoutState, isVertical: Boolean, dxFraction: Float, dyFraction: Float): LayoutState {
        val delta = if (isVertical) dxFraction else dyFraction
        return state.copy(splitFraction = (state.splitFraction + delta).coerceIn(0.1f, 0.9f))
    }

    /** Utility per il tocco: sposta il centro dell'inserto PiP. */
    fun dragPip(state: LayoutState, dxFraction: Float, dyFraction: Float, viewportAspect: Float, streamAspect: Float): LayoutState {
        val w = state.pipWidth.coerceIn(0.2f, 0.95f)
        val h = w * viewportAspect / max(streamAspect, 1e-3f)
        val cx = (state.pipCenterX + dxFraction).coerceIn(w / 2f, 1f - w / 2f)
        val cy = (state.pipCenterY + dyFraction).coerceIn(h / 2f, 1f - h / 2f)
        return state.copy(pipCenterX = cx, pipCenterY = cy)
    }

    /** Ciclo di dimensioni dell'inserto PiP su doppio tocco. */
    fun cyclePipSize(state: LayoutState): LayoutState {
        val sizes = floatArrayOf(0.4f, 0.55f, 0.7f, 0.85f)
        var next = sizes[0]
        for (s in sizes) {
            if (s > state.pipWidth + 1e-3f) {
                next = s
                break
            }
        }
        return state.copy(pipWidth = min(next, 0.95f))
    }
}

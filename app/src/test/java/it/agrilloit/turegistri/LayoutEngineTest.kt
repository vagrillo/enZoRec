package it.agrilloit.turegistri

import it.agrilloit.turegistri.model.LayoutEngine
import it.agrilloit.turegistri.model.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEngineTest {

    private val landscape = 16f / 9f // buffer 1280x720 visto dritto in portrait
    private val portraitViewport = 720f / 1280f // 9:16

    @Test
    fun `cover crop riempie tagliando i lati quando il flusso è più largo`() {
        val crop = LayoutEngine.coverCrop(streamAspect = landscape, regionAspect = portraitViewport)
        // Il flusso è più largo della regione: taglio orizzontale, altezza piena.
        assertEquals(0f, crop.v0, 1e-4f)
        assertEquals(1f, crop.v1, 1e-4f)
        assertTrue(crop.u0 > 0f && crop.u0 < 0.5f)
        assertTrue(crop.u1 > 0.5f && crop.u1 < 1f)
        // La porzione visibile ha esattamente l'aspect della regione.
        val visible = (crop.u1 - crop.u0) * landscape
        assertEquals(portraitViewport, visible, 1e-3f)
    }

    @Test
    fun `cover crop riempie tagliando sopra e sotto quando il flusso è più alto`() {
        val crop = LayoutEngine.coverCrop(streamAspect = portraitViewport, regionAspect = landscape)
        assertEquals(0f, crop.u0, 1e-4f)
        assertEquals(1f, crop.u1, 1e-4f)
        // Finestra visibile: altezza * regionAspect == streamAspect.
        val visibleH = (crop.v1 - crop.v0) * landscape
        assertEquals(portraitViewport, visibleH, 1e-3f)
    }

    @Test
    fun `split verticale produce due regioni adiacenti con frazione corretta`() {
        val state = LayoutEngine.LayoutState(
            mode = LayoutMode.SPLIT_V, splitFraction = 0.4f, fitFrame = false
        )
        val placements = LayoutEngine.compute(
            state, listOf(landscape, landscape), portraitViewport, cameraCount = 2
        )
        assertEquals(2, placements.size)
        val a = placements[0]
        val b = placements[1]
        assertEquals(0f, a.region.x, 1e-5f)
        assertEquals(0.4f, a.region.w, 1e-5f)
        assertEquals(0.4f, b.region.x, 1e-5f)
        assertEquals(0.6f, b.region.w, 1e-5f)
        // Nessuna sovrapposizione né buco.
        assertEquals(1f, a.region.w + b.region.w, 1e-5f)
    }

    @Test
    fun `split orizzontale copre l'altezza`() {
        val state = LayoutEngine.LayoutState(
            mode = LayoutMode.SPLIT_H, splitFraction = 0.3f, fitFrame = false
        )
        val placements = LayoutEngine.compute(
            state, listOf(landscape, landscape), portraitViewport, cameraCount = 2
        )
        assertEquals(2, placements.size)
        assertEquals(0.3f, placements[0].region.h, 1e-5f)
        assertEquals(0.3f, placements[1].region.y, 1e-5f)
        assertEquals(0.7f, placements[1].region.h, 1e-5f)
    }

    @Test
    fun `pip ha sfondo pieno e inserto con crop pieno`() {
        val state = LayoutEngine.LayoutState(
            mode = LayoutMode.PIP, pipWidth = 0.5f, pipCenterX = 0.5f, pipCenterY = 0.5f
        )
        val placements = LayoutEngine.compute(
            state, listOf(landscape, landscape), portraitViewport, cameraCount = 2
        )
        assertEquals(2, placements.size)
        assertEquals(0, placements[0].cameraIndex)
        assertEquals(1f, placements[0].region.w, 1e-5f)
        assertEquals(1, placements[1].cameraIndex)
        assertEquals(LayoutEngine.Crop.FULL, placements[1].crop)
        // L'inserto mantiene l'aspect del flusso.
        val inset = placements[1].region
        assertEquals(landscape, inset.w * portraitViewport / inset.h, 1e-3f)
        // L'inserto è dentro il viewport.
        assertTrue(inset.x >= 0f && inset.y >= 0f && inset.x2 <= 1f && inset.y2 <= 1f)
    }

    @Test
    fun `single b con una sola camera degrada alla camera A`() {
        val state = LayoutEngine.LayoutState(mode = LayoutMode.SINGLE_B)
        val placements = LayoutEngine.compute(
            state, listOf(landscape), portraitViewport, cameraCount = 1
        )
        assertEquals(1, placements.size)
        assertEquals(0, placements[0].cameraIndex)
    }

    @Test
    fun `drag split resta nei limiti`() {
        var state = LayoutEngine.LayoutState(mode = LayoutMode.SPLIT_V, splitFraction = 0.5f)
        state = LayoutEngine.dragSplit(state, isVertical = true, dxFraction = 10f, dyFraction = 0f)
        assertEquals(0.9f, state.splitFraction, 1e-5f)
        state = LayoutEngine.dragSplit(state, isVertical = true, dxFraction = -10f, dyFraction = 0f)
        assertEquals(0.1f, state.splitFraction, 1e-5f)
    }

    @Test
    fun `ciclo pip size cresce e riparte`() {
        var state = LayoutEngine.LayoutState(pipWidth = 0.55f)
        state = LayoutEngine.cyclePipSize(state)
        assertEquals(0.7f, state.pipWidth, 1e-5f)
        state = LayoutEngine.cyclePipSize(state)
        state = LayoutEngine.cyclePipSize(state)
        assertEquals(0.4f, state.pipWidth, 1e-5f)
    }

    @Test
    fun `fit mode mostra il fotogramma intero con letterbox`() {
        val state = LayoutEngine.LayoutState(
            mode = LayoutMode.SPLIT_V, splitFraction = 0.5f, fitFrame = true
        )
        val placements = LayoutEngine.compute(
            state, listOf(landscape, landscape), portraitViewport, cameraCount = 2
        )
        assertEquals(2, placements.size)
        val panels = listOf(0f to 0.5f, 0.5f to 1f)
        for ((i, p) in placements.withIndex()) {
            // Nessun crop e regione con l'aspect del flusso.
            assertEquals(LayoutEngine.Crop.FULL, p.crop)
            val regionAspect = p.region.w * portraitViewport / p.region.h
            assertEquals(landscape, regionAspect, 1e-3f)
            // La regione adattata sta dentro il proprio pannello.
            val (p0, p1) = panels[i]
            assertTrue(p.region.x >= p0 - 1e-4f && p.region.x2 <= p1 + 1e-4f)
            assertTrue(p.region.y >= 0f && p.region.y2 <= 1f + 1e-4f)
        }
    }

    @Test
    fun `fit inside mantiene il centro della regione`() {
        val region = LayoutEngine.Region(0f, 0f, 1f, 0.5f)
        val fitted = LayoutEngine.fitInside(region, landscape, portraitViewport)
        assertEquals(region.x + region.w / 2f, fitted.x + fitted.w / 2f, 1e-4f)
        assertEquals(region.y + region.h / 2f, fitted.y + fitted.h / 2f, 1e-4f)
        // Pannello 1 x 0.5 con viewport 9:16 -> aspect pannello 1.125 (largo);
        // flusso 1.777 più largo: fit in larghezza, altezza ridotta.
        assertEquals(1f, fitted.w, 1e-4f)
        assertEquals(1f * portraitViewport / landscape, fitted.h, 1e-3f)
        val fittedAspect = fitted.w * portraitViewport / fitted.h
        assertEquals(landscape, fittedAspect, 1e-3f)
    }

    @Test
    fun `fit mode disattivata usa il cover crop`() {
        val state = LayoutEngine.LayoutState(
            mode = LayoutMode.SPLIT_V, splitFraction = 0.5f, fitFrame = false
        )
        val placements = LayoutEngine.compute(
            state, listOf(landscape, landscape), portraitViewport, cameraCount = 2
        )
        // Con cover-crop la regione è esattamente il pannello.
        assertEquals(0.5f, placements[0].region.w, 1e-5f)
        assertTrue(placements[0].crop !== LayoutEngine.Crop.FULL)
    }
}

package com.riffle.core.domain.comic.panel

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelOrdererTest {

    private val orderer = PanelOrderer()

    @Test
    fun `2x2 grid is ordered top-left, top-right, bottom-left, bottom-right`() {
        val panels = listOf(
            // Deliberately shuffled input to prove we don't just return insertion order.
            PanelRegion(x = 210, y = 290, width = 170, height = 250),
            PanelRegion(x = 20, y = 20, width = 170, height = 250),
            PanelRegion(x = 210, y = 20, width = 170, height = 250),
            PanelRegion(x = 20, y = 290, width = 170, height = 250),
        )
        val ordered = orderer.order(panels)
        assertEquals(listOf(20, 210, 20, 210), ordered.map { it.x })
        assertEquals(listOf(20, 20, 290, 290), ordered.map { it.y })
    }

    @Test
    fun `T-shape is top wide panel then bottom two left-to-right`() {
        val panels = listOf(
            PanelRegion(x = 210, y = 290, width = 170, height = 250),
            PanelRegion(x = 20, y = 20, width = 360, height = 250),
            PanelRegion(x = 20, y = 290, width = 170, height = 250),
        )
        val ordered = orderer.order(panels)
        assertEquals(20, ordered[0].y)
        assertEquals(360, ordered[0].width)
        assertEquals(20, ordered[1].x)
        assertEquals(290, ordered[1].y)
        assertEquals(210, ordered[2].x)
        assertEquals(290, ordered[2].y)
    }

    @Test
    fun `staircase (each panel starts partway down the previous one) reads top-to-bottom`() {
        val panels = listOf(
            PanelRegion(x = 20, y = 400, width = 300, height = 100),
            PanelRegion(x = 20, y = 20, width = 300, height = 100),
            PanelRegion(x = 20, y = 210, width = 300, height = 100),
        )
        val ordered = orderer.order(panels)
        assertEquals(listOf(20, 210, 400), ordered.map { it.y })
    }

    @Test
    fun `single-panel page returns that panel unchanged`() {
        val panel = PanelRegion(x = 0, y = 0, width = 400, height = 560)
        assertEquals(listOf(panel), orderer.order(listOf(panel)))
    }

    @Test
    fun `panels with slight y-jitter still cluster into the same row`() {
        // Real detection often lands panels off by a few pixels; they should still be one row.
        val panels = listOf(
            PanelRegion(x = 210, y = 24, width = 170, height = 246),
            PanelRegion(x = 20, y = 20, width = 170, height = 250),
        )
        val ordered = orderer.order(panels)
        assertEquals(20, ordered[0].x)
        assertEquals(210, ordered[1].x)
    }
}

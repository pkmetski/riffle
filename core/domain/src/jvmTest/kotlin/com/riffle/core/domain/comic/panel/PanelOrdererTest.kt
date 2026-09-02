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

    // --- Regression tests for issue #791 ---
    // The shortest-member sharesRowWith rule (introduced for #780) is order-dependent and breaks
    // the three layouts below. All three should be a single row sorted left-to-right.

    @Test
    fun `split middle column does not exile the bottom-half continuation panel`() {
        // CE1 from issue #791 (800×1200 page).
        // A tall left (A) and tall right (D) flank a short top-middle (B) and a taller
        // bottom-middle (C). B joining first makes it the shortest member; C then has zero
        // overlap with B and is wrongly exiled, producing A,B,D,C instead of A,B,C,D.
        val a = PanelRegion(x = 20, y = 40, width = 240, height = 560)   // tall left
        val b = PanelRegion(x = 280, y = 40, width = 240, height = 170)  // short mid-top
        val c = PanelRegion(x = 280, y = 230, width = 240, height = 370) // taller mid-bot
        val d = PanelRegion(x = 540, y = 40, width = 240, height = 560)  // tall right
        val ordered = orderer.order(listOf(a, b, d, c))                  // deliberately shuffled
        assertEquals("all four panels should be in one row sorted left-to-right",
            listOf(20, 280, 280, 540), ordered.map { it.x })
        assertEquals("within same x, top panel (B) precedes continuation (C)",
            listOf(40, 40, 230, 40), ordered.map { it.y })
    }

    @Test
    fun `leftward-descending terrace reads left-to-right as a single band`() {
        // CE2 from issue #791 (terrace: each panel starts a little lower and further left).
        // Under the shortest-member rule A is exiled because it overlaps C (the shortest
        // member, h=200) by only 40% → output B,C,A instead of A,B,C.
        val a = PanelRegion(x = 0, y = 120, width = 180, height = 280)   // bottom-left
        val b = PanelRegion(x = 200, y = 60, width = 180, height = 280)  // middle
        val c = PanelRegion(x = 400, y = 0, width = 180, height = 200)   // short top-right
        val ordered = orderer.order(listOf(c, b, a))                     // deliberately shuffled
        assertEquals("terrace panels form one row, read left-to-right",
            listOf(0, 200, 400), ordered.map { it.x })
    }

    @Test
    fun `caption strip does not collapse the band and exile the panel below it`() {
        // CE3 from issue #791.
        // A thin caption (D, h=40) joins the row first and becomes the shortest member.
        // B (which starts below D's bottom) then has zero overlap with D and is exiled,
        // producing A,D,C,B instead of A,D,B,C.
        val a = PanelRegion(x = 20, y = 20, width = 180, height = 280)  // tall left
        val d = PanelRegion(x = 220, y = 20, width = 180, height = 40)  // thin caption
        val b = PanelRegion(x = 220, y = 80, width = 180, height = 220) // panel below caption
        val c = PanelRegion(x = 620, y = 20, width = 180, height = 280) // tall right
        val ordered = orderer.order(listOf(a, d, c, b))                 // deliberately shuffled
        assertEquals("all four in one row, left-to-right (d before b at same x by y)",
            listOf(20, 220, 220, 620), ordered.map { it.x })
        assertEquals("D (top) precedes B (below it) at the same x column",
            listOf(20, 20, 80, 20), ordered.map { it.y })
    }

    @Test
    fun `CE1 result is stable regardless of input emission order`() {
        // Issue #791 also notes the shortest-member rule is order-dependent; verify the fix
        // produces A,B,C,D for all permutations of the equal-y triplet.
        val a = PanelRegion(x = 20, y = 40, width = 240, height = 560)
        val b = PanelRegion(x = 280, y = 40, width = 240, height = 170)
        val c = PanelRegion(x = 280, y = 230, width = 240, height = 370)
        val d = PanelRegion(x = 540, y = 40, width = 240, height = 560)
        val expectedX = listOf(20, 280, 280, 540)
        val expectedY = listOf(40, 40, 230, 40)
        for (input in listOf(
            listOf(a, b, c, d), listOf(d, b, a, c), listOf(b, d, a, c), listOf(a, d, b, c),
        )) {
            val ordered = orderer.order(input)
            assertEquals("emission order $input → wrong x sequence", expectedX, ordered.map { it.x })
            assertEquals("emission order $input → wrong y sequence", expectedY, ordered.map { it.y })
        }
    }

    // --- Regression tests for issue #896 ---
    // The exile check used member.y <= candidate.y, which fired even for panels sharing the
    // same y-coordinate, placing the second-processed equal-y panel into a new row and
    // producing wrong reading order. Fix: strictly less than (<).

    @Test
    fun `two panels sharing identical y are placed in the same row left-to-right (issue 896)`() {
        // Regression: exile check member.y <= candidate.y fired on equal-y panels when the
        // member x-overlapped the candidate. Fix: member.y < candidate.y (strictly less).
        val left = PanelRegion(x = 0, y = 609, width = 430, height = 459)    // y=609..1068
        val right = PanelRegion(x = 449, y = 609, width = 500, height = 459) // y=609..1068
        // Pass right first so right enters the row before left is processed (triggers old bug).
        val ordered = orderer.order(listOf(right, left))
        assertEquals("left panel (x=0) must come first in reading order", 0, ordered[0].x)
        assertEquals("right panel (x=449) must come second in reading order", 449, ordered[1].x)
    }

    @Test
    fun `panel starting one pixel below a spanning member is still exiled to the next row`() {
        // Boundary: member.y=0 < candidate.y=1 (strictly less) — exile still fires.
        // Verifies the fix (< instead of <=) does NOT break exile for the non-equal-y case.
        val spanning = PanelRegion(x = 0, y = 0, width = 400, height = 500)   // y=0..500
        val lower = PanelRegion(x = 200, y = 1, width = 200, height = 499)    // y=1..500, x-overlaps
        // exile: member.y(0) < candidate.y(1) ✓, overlap fraction=(500-1)/499=1.0 ≥ 0.5 ✓
        val ordered = orderer.order(listOf(lower, spanning))
        assertEquals("spanning panel must be first", spanning, ordered[0])
        assertEquals("lower panel must be exiled to second row", lower, ordered[1])
    }

    @Test
    fun `tall spanning panel still exiles the lower panel in its own column (issue 780 shape)`() {
        // Regression guard: the union+exile fix must not re-introduce the #780 bug.
        // Mirrors the real fixture geometry from the #780 test in PanelDetectorImageTest:
        //   tall-left   x=0..460,  y=200..1080
        //   upper-right x=520..800, y=200..510  (no x-overlap with tall-left)
        //   lower-right x=446..800, y=510..1120 (x-overlaps tall-left; extends past its bottom)
        // Correct order: tall-left → upper-right → lower-right.
        val tallLeft = PanelRegion(x = 0, y = 200, width = 460, height = 880)    // x=0..460, y=200..1080
        val upperRight = PanelRegion(x = 520, y = 200, width = 280, height = 310) // x=520..800, y=200..510
        val lowerRight = PanelRegion(x = 446, y = 510, width = 354, height = 610) // x=446..800, y=510..1120
        val ordered = orderer.order(listOf(lowerRight, upperRight, tallLeft))     // shuffled
        assertEquals(3, ordered.size)
        assertEquals("tall-left must be first", tallLeft, ordered[0])
        assertEquals("upper-right must be second", upperRight, ordered[1])
        assertEquals("lower-right must be last", lowerRight, ordered[2])
    }
}

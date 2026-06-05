package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadaloudReserveTest {

    @Test
    fun `no reserve when player closed`() {
        assertEquals(0, readaloudReserveCssPx(playerOpen = false, paginated = true, overlayHeightDp = 120f))
    }

    @Test
    fun `no reserve in scroll mode even with player open`() {
        // Scroll/vertical mode: text isn't pinned to a page, so the player covering the bottom
        // sliver is a non-issue (one small scroll reveals it). Deliberately reserves nothing.
        assertEquals(0, readaloudReserveCssPx(playerOpen = true, paginated = false, overlayHeightDp = 120f))
    }

    @Test
    fun `reserves the overlay height when player open and paginated`() {
        assertEquals(120, readaloudReserveCssPx(playerOpen = true, paginated = true, overlayHeightDp = 120f))
    }

    @Test
    fun `rounds fractional dp to nearest css px`() {
        assertEquals(117, readaloudReserveCssPx(playerOpen = true, paginated = true, overlayHeightDp = 116.6f))
        assertEquals(116, readaloudReserveCssPx(playerOpen = true, paginated = true, overlayHeightDp = 116.4f))
    }

    @Test
    fun `negative measured height clamps to zero`() {
        assertEquals(0, readaloudReserveCssPx(playerOpen = true, paginated = true, overlayHeightDp = -5f))
    }

    @Test
    fun `apply js activates rule and sets var when reserve positive`() {
        val js = readaloudReserveApplyJs(140)
        assertTrue(js.contains("classList.add('riffle-ra-on')"))
        assertTrue(js.contains("setProperty('--riffle-ra-reserve', '140px')"))
    }

    @Test
    fun `apply js clears rule and var when reserve is zero`() {
        val js = readaloudReserveApplyJs(0)
        assertTrue(js.contains("classList.remove('riffle-ra-on')"))
        assertTrue(js.contains("removeProperty('--riffle-ra-reserve')"))
    }

    @Test
    fun `reserve css composes the page margin with the floating-stack height`() {
        val css = readaloudReserveCss()
        // gap above the player == the page's own bottom margin (scales with --USER__pageMargins),
        // plus the floating stack height carried in the var.
        assertTrue(css.contains("var(--RS__pageGutter"))
        assertTrue(css.contains("var(--USER__pageMargins, 1)"))
        assertTrue(css.contains("var(--riffle-ra-reserve, 0px)"))
        assertTrue(css.contains("padding-bottom"))
        // gated on the active class, doubled :root to win specificity over the margins override
        assertTrue(css.contains(":root.riffle-ra-on:root"))
    }

    @Test
    fun `injection js is idempotent by stable id`() {
        val js = readaloudReserveInjectionJs()
        assertTrue(js.contains("'riffle-readaloud-reserve'"))
        assertTrue(js.contains("if (document.getElementById(id)) return"))
    }
}

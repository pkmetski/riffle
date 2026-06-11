package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadaloudReserveTest {

    @Test
    fun `no reserve when readaloud unavailable`() {
        assertEquals(0, readaloudReserveDp(readaloudAvailable = false, paginated = true))
    }

    @Test
    fun `no reserve in scroll mode even when readaloud available`() {
        // Scroll/vertical mode: text isn't pinned to a page, so the player floating over the bottom
        // sliver is a non-issue (one small scroll reveals it). Deliberately reserves nothing.
        assertEquals(0, readaloudReserveDp(readaloudAvailable = true, paginated = false))
    }

    @Test
    fun `reserves the bar height when readaloud available and paginated`() {
        // Gated on availability, NOT on the player being open — so the value is stable across the
        // session and toggling the player never re-paginates.
        assertEquals(READALOUD_RESERVE_DP, readaloudReserveDp(readaloudAvailable = true, paginated = true))
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
    fun `reserve css honors the page bottom margin plus the bar height`() {
        val css = readaloudReserveCss()
        // gap above the player == the page's own bottom margin (tracks the margins slider via
        // --USER__pageMargins, symmetric with the top), plus the bar height carried in the var.
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

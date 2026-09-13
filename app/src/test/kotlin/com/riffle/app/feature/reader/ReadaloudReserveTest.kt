package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadaloudReserveTest {

    @Test
    fun `no reserve when readaloud unavailable`() {
        // "unavailable" now means the player is not open — same claim, param renamed readaloudOpen
        assertEquals(0, readaloudReserveDp(readaloudOpen = false, paginated = true))
    }

    @Test
    fun `no reserve in scroll mode even when readaloud available`() {
        // Scroll/vertical mode: text isn't pinned to a page, so the player floating over the bottom
        // sliver is a non-issue (one small scroll reveals it). Deliberately reserves nothing.
        assertEquals(0, readaloudReserveDp(readaloudOpen = true, paginated = false))
    }

    @Test
    fun `reserves the bar height when readaloud available and paginated`() {
        assertEquals(READALOUD_RESERVE_DP, readaloudReserveDp(readaloudOpen = true, paginated = true))
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
    fun `reserve css sets padding-bottom to exactly the reserve var`() {
        val css = readaloudReserveCss()
        // Only the reserve height — no gutter term. The container View padding already provides
        // the page bottom margin, so adding --RS__pageGutter here would double-count it.
        assertTrue(css.contains("var(--riffle-ra-reserve, 0px)"))
        assertTrue(css.contains("padding-bottom"))
        // gated on the active class, doubled :root to win specificity over the margins override
        assertTrue(css.contains(":root.riffle-ra-on:root"))
        // No gutter multiplication — that was the double-margin bug
        assertTrue(!css.contains("--RS__pageGutter"))
        assertTrue(!css.contains("--USER__pageMargins"))
    }

    @Test
    fun `injection js is idempotent by stable id`() {
        val js = readaloudReserveInjectionJs()
        assertTrue(js.contains("'riffle-readaloud-reserve'"))
        assertTrue(js.contains("if (document.getElementById(id)) return"))
    }
}

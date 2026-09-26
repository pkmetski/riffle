package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaginatedLayoutLockTest {

    @Test
    fun installScriptInjectsStyleWithStableId() {
        assertTrue(
            PaginatedLayoutLock.INSTALL_SCRIPT.contains("riffle-paginated-layout-lock"),
            "stable element id must be present so the idempotent guard works",
        )
    }

    @Test
    fun installScriptIsIdempotent() {
        assertTrue(
            PaginatedLayoutLock.INSTALL_SCRIPT.contains("document.getElementById"),
            "script must early-return when element already exists to avoid duplicate styles",
        )
    }

    @Test
    fun installScriptEnforcesHeightWithImportant() {
        assertTrue(
            PaginatedLayoutLock.INSTALL_SCRIPT.contains("height: 100vh !important"),
            "paginated height lock must use !important to beat hostile publisher CSS",
        )
    }

    @Test
    fun installScriptEnforcesOverflowYHiddenWithImportant() {
        // overflow-y: hidden !important blocks the publisher-CSS vertical-scroll bypass
        // (e.g. `overflow-y: scroll !important` on :root) without touching overflow-x —
        // Readium navigates columns by changing scrollLeft on the horizontal axis.
        assertTrue(
            PaginatedLayoutLock.INSTALL_SCRIPT.contains("overflow-y: hidden !important"),
            "overflow-y must be hidden with !important to prevent vertical scrolling without clipping horizontal column navigation",
        )
    }

    @Test
    fun installScriptScopedToPaginatedModeOnly() {
        assertTrue(
            PaginatedLayoutLock.INSTALL_SCRIPT.contains(":not([style*=\"readium-scroll-on\"])"),
            "CSS selector must be gated on :not([style*=readium-scroll-on]) so scroll/continuous mode is unaffected",
        )
    }

    @Test
    fun installScriptDoesNotTargetScrollMode() {
        // The guard must exclude scroll mode; it must NOT unconditionally target :root so
        // vertical/continuous readers are not accidentally locked to viewport height.
        val css = PaginatedLayoutLock.INSTALL_SCRIPT
        assertFalse(
            css.contains(":root {"),
            "rule must be scoped to paginated mode via :not guard, not bare :root",
        )
        assertFalse(
            css.contains(":root\n"),
            "rule must be scoped to paginated mode via :not guard, not bare :root",
        )
    }
}

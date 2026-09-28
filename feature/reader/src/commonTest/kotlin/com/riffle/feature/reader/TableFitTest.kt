package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertTrue

class TableFitTest {

    @Test
    fun installScriptForcesTableWidthWithImportant() {
        assertTrue(
            TableFit.INSTALL_SCRIPT.contains("width: 100% !important"),
            "table-fit must force width: 100% !important to override publisher fixed pixel widths",
        )
    }

    @Test
    fun installScriptUsesFixedLayoutWithImportant() {
        assertTrue(
            TableFit.INSTALL_SCRIPT.contains("table-layout: fixed !important"),
            "table-fit must use table-layout: fixed !important to disable auto minimum-content sizing",
        )
    }

    @Test
    fun installScriptZeroesOutCellMinWidthWithImportant() {
        // O'Reilly epub.css sets min-width: 96px on td/th; 5 columns × 96px = 480px
        // exceeds the ~368px page width and forces overflow even under fixed layout.
        assertTrue(
            TableFit.INSTALL_SCRIPT.contains("min-width: 0 !important"),
            "table-fit must zero out publisher min-width on cells so fixed layout can distribute columns within 100%",
        )
    }

    @Test
    fun installScriptIsIdempotentViaStableId() {
        assertTrue(
            TableFit.INSTALL_SCRIPT.contains("riffle-table-fit"),
            "stable element id must be present so the idempotent guard avoids duplicate style injection",
        )
        assertTrue(
            TableFit.INSTALL_SCRIPT.contains("document.getElementById"),
            "script must early-return when element already exists",
        )
    }

    @Test
    fun installScriptGuardsAgainstNullHead() {
        assertTrue(
            TableFit.INSTALL_SCRIPT.contains("document.head"),
            "script must reference document.head and guard against headless EPUB chapters",
        )
        assertTrue(
            TableFit.INSTALL_SCRIPT.contains("if (!document.head)"),
            "script must guard with if (!document.head) return to avoid TypeError on headless chapters",
        )
    }
}

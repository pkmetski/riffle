package com.riffle.app.feature.reader.highlights

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightsDomPatchTest {

    @Test
    fun `Recolor JS targets the accent bar and any adjacent note`() {
        val js = HighlightsDomPatch.Recolor(
            annotationId = "abc-123",
            accentCssRgba = "rgba(255,193,0,1)",
        ).applyJs()
        // Escaped id lands verbatim inside the JS querySelector string.
        assertTrue(js, js.contains("\"abc-123\""))
        assertTrue(js, js.contains("rgba(255,193,0,1)"))
        // The recolour must touch BOTH the paragraph (via closest('p')) AND any aside that
        // carries the same data-ann-id — otherwise a note's border colour drifts from the bar.
        assertTrue("recolor JS must find nodes by data-ann-id", js.contains("data-ann-id="))
        assertTrue(
            "recolor JS must fall through to the paragraph OR figure via closest('p, figure')",
            js.contains("closest('p, figure')"),
        )
        assertTrue(
            "recolor JS must treat a <figure> host as directly-target-able (fix 2026-07-10)",
            js.contains("FIGURE"),
        )
        assertTrue("recolor JS must set border-left-color", js.contains("border-left-color"))
        assertTrue("recolor JS must use setProperty with 'important' to defeat ReadiumCSS theming",
            js.contains("'important'") || js.contains("!important"))
    }

    @Test
    fun `SetNote with non-null text creates an aside or updates textContent`() {
        val js = HighlightsDomPatch.SetNote(
            annotationId = "id-9",
            accentCssRgba = "rgba(0,128,255,1)",
            noteText = "Hello",
        ).applyJs()
        assertTrue(js, js.contains("\"Hello\""))
        assertTrue("must create an ASIDE element when missing", js.contains("createElement('aside')"))
        assertTrue("must set the class and data-ann-id on new asides", js.contains("'riffle-note'"))
        assertTrue("must look up the existing aside by its own data-ann-id (adjacency is unreliable through Readium's HTMLInjector)",
            js.contains("aside.riffle-note[data-ann-id="))
        assertTrue("must update textContent (not innerHTML — avoid injection)", js.contains("textContent"))
        // Fix 2026-07-10: for text-figure-text annotations the aside must sit after the LAST
        // element carrying this data-ann-id, not the first — otherwise the note splits the
        // annotation body. Loop from `nodes.length - 1` picks the last host block; the guard
        // skips any existing aside with the same id so we don't chain a second aside onto the first.
        assertTrue(
            "SetNote must scan nodes from the END to place the aside after the last chunk",
            js.contains("i = nodes.length - 1"),
        )
    }

    @Test
    fun `SetNote with null text removes the aside`() {
        val js = HighlightsDomPatch.SetNote(
            annotationId = "id-9",
            accentCssRgba = "rgba(0,0,0,1)",
            noteText = null,
        ).applyJs()
        assertTrue("null note must branch to remove()", js.contains("aside.remove()"))
        // The JS body is one function that inspects `note === null` at runtime; the null path
        // early-returns after the remove, so the createElement branch is dead code — that's fine.
        // What we DO want to pin: the "note is null" condition is present so the runtime path
        // actually skips creation.
        assertTrue("null-note path must be gated on `note === null`", js.contains("note === null"))
    }

    @Test
    fun `Remove JS deletes the paragraph and any adjacent note`() {
        val js = HighlightsDomPatch.Remove("id-42").applyJs()
        assertTrue(js.contains("\"id-42\""))
        // Deletes the surrounding <p>...
        assertTrue("Remove JS must delete the containing paragraph", js.contains("p.remove()"))
        // ...and the aside carrying the same data-ann-id, looked up directly (not by sibling adjacency).
        assertTrue("Remove JS must also drop the aside with the same data-ann-id",
            js.contains("aside.remove()"))
        assertTrue("Remove JS must look up the aside directly by data-ann-id",
            js.contains("aside.riffle-note[data-ann-id="))
        assertTrue(js.contains("closest('p')"))
        assertTrue(
            "Remove JS must also drop any figure.riffle-fig block carrying this id (fix 2026-07-10)",
            js.contains("figure.riffle-fig[data-ann-id="),
        )
        // Multi-chunk annotations (text-figure-text) emit MULTIPLE <span class="riffle-hl"> — a
        // querySelector would only drop the first chunk's <p>. Remove JS must loop over every
        // match and drop each host paragraph.
        assertTrue(
            "Remove JS must iterate span.riffle-hl matches (querySelectorAll) to catch every chunk",
            js.contains("querySelectorAll('span.riffle-hl["),
        )
    }

    @Test
    fun `annotationId is JS-escaped so a quote in the id cannot break out`() {
        // A pathological id shouldn't happen in practice, but the JS quoting must be safe.
        val js = HighlightsDomPatch.Recolor(
            annotationId = "id\"; document.body.remove(); //",
            accentCssRgba = "red",
        ).applyJs()
        // The literal double-quote from the id must be escaped, not passed raw.
        assertTrue("id containing a quote must be escaped", js.contains("id\\\";"))
        // The injected code must not appear un-escaped as a top-level statement.
        assertFalse("injection attempt must remain inside the string literal",
            js.contains("\"id\"; document.body.remove()"))
    }
}

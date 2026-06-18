package com.riffle.app.feature.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightOverlapTest {

    // ---- Text overlap: one snippet must contain the other ----

    @Test
    fun exactSameWordSamePosition_overlaps() {
        assertTrue(
            highlightOverlapsAtSamePosition("hydrogen", " atom is", "hydrogen", " atom is")
        )
    }

    @Test
    fun unrelatedWords_noOverlap() {
        assertFalse(
            highlightOverlapsAtSamePosition("oxygen", " molecule", "hydrogen", " atom")
        )
    }

    // ---- Position disambiguation: same word, different occurrences ----

    @Test
    fun sameWordDifferentPositions_noOverlap() {
        // Two "hydrogen" highlights at different positions in the chapter.
        assertFalse(
            highlightOverlapsAtSamePosition(
                newSnippet = "hydrogen",
                newAfter   = " atom is the",
                existSnippet = "hydrogen",
                existAfter   = " bond forms when",
            )
        )
    }

    // ---- Larger new selection covering a smaller existing one ----

    @Test
    fun largerNewCoversExisting_overlaps() {
        assertTrue(
            highlightOverlapsAtSamePosition(
                newSnippet = "the hydrogen atom",
                newAfter   = " is the lightest",
                existSnippet = "hydrogen",
                existAfter   = " atom is the lightest",
            )
        )
    }

    // ---- Smaller new selection inside an existing larger one ----

    @Test
    fun smallerNewInsideExisting_overlaps() {
        assertTrue(
            highlightOverlapsAtSamePosition(
                newSnippet = "hydrogen",
                newAfter   = " atom is the lightest",
                existSnippet = "the hydrogen atom",
                existAfter   = " is the lightest",
            )
        )
    }

    // ---- End of existing highlight matches start of new ----

    @Test
    fun newAtEndOfExisting_overlaps() {
        assertTrue(
            highlightOverlapsAtSamePosition(
                newSnippet = "atom",
                newAfter   = " is the lightest",
                existSnippet = "hydrogen atom",
                existAfter   = " is the lightest",
            )
        )
    }

    // ---- Legacy highlights with no stored after-text fall through on text overlap alone ----

    @Test
    fun existingHasNoContext_overlapsOnTextAlone() {
        assertTrue(
            highlightOverlapsAtSamePosition(
                newSnippet = "hydrogen",
                newAfter   = " atom",
                existSnippet = "hydrogen",
                existAfter   = "",
            )
        )
    }

    @Test
    fun existingHasNoContext_noOverlapIfDifferentWord() {
        assertFalse(
            highlightOverlapsAtSamePosition(
                newSnippet = "water",
                newAfter   = " molecules",
                existSnippet = "hydrogen",
                existAfter   = "",
            )
        )
    }

    // ---- Blank/empty edge cases ----

    @Test
    fun blankExistingSnippet_noOverlap() {
        assertFalse(
            highlightOverlapsAtSamePosition("hydrogen", " atom", "   ", " atom")
        )
    }

    @Test
    fun blankNewSnippet_noOverlap() {
        assertFalse(
            highlightOverlapsAtSamePosition("   ", " atom", "hydrogen", " atom")
        )
    }
}

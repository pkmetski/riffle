package com.riffle.app.feature.reader.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decision core of intra-sentence page following: as a single narrated sentence plays, decide
 * which of the columns it spans should be on screen, and signal a page turn ONLY when that column
 * changes. These tests pin the behaviours that make the follow robust — drift lead, snap-on-change
 * (so it never fights a manual turn between break points), backward-seek reversal, 3+ column
 * sentences, and the no-timing fallback.
 */
class NarratedColumnProgressionTest {

    // ── single column: never turns ─────────────────────────────────────────────────────────────

    @Test fun singleColumnSentenceNeverTurns() {
        val p = NarratedColumnProgression(lead = 0.0)
        p.onSentence(listOf(1.0))
        assertNull(p.advance(0.0))
        assertNull(p.advance(0.5))
        assertNull(p.advance(1.0))
    }

    @Test fun unmeasuredSentenceNeverTurns() {
        val p = NarratedColumnProgression(lead = 0.0)
        p.onSentence(emptyList()) // measurement was "off"/"scroll" → no columns
        assertNull(p.advance(0.0))
        assertNull(p.advance(0.9))
    }

    // ── two columns: snap-on-change ─────────────────────────────────────────────────────────────

    @Test fun twoColumnTurnsOnceWhenCrossingTheBreak() {
        val p = NarratedColumnProgression(lead = 0.0)
        p.onSentence(listOf(0.6, 1.0)) // 60% of the sentence's width is in column 0
        assertNull(p.advance(0.0))      // starts on column 0 (no turn — auto-follow already put us there)
        assertNull(p.advance(0.3))      // still column 0
        assertNull(p.advance(0.59))     // still column 0
        assertEquals(1, p.advance(0.60)) // crosses the break → turn to column 1
        assertNull(p.advance(0.7))      // already column 1 → no repeat turn
        assertNull(p.advance(0.99))
    }

    // ── manual override: ticks within a column never re-snap ────────────────────────────────────

    @Test fun ticksWithinAColumnDoNotReSnap() {
        // Many position polls land inside one column; only the boundary crossing should snap, so a
        // user who manually paged away mid-column is not yanked back on every tick.
        val p = NarratedColumnProgression(lead = 0.0)
        p.onSentence(listOf(0.6, 1.0))
        for (f in listOf(0.61, 0.65, 0.7, 0.8, 0.9, 0.95)) {
            val turn = p.advance(f)
            if (f == 0.61) assertEquals(1, turn) else assertNull(turn)
        }
    }

    // ── backward seek reverses ──────────────────────────────────────────────────────────────────

    @Test fun backwardSeekReturnsToTheEarlierColumn() {
        val p = NarratedColumnProgression(lead = 0.0)
        p.onSentence(listOf(0.6, 1.0))
        assertEquals(1, p.advance(0.8)) // played into column 1
        assertEquals(0, p.advance(0.2)) // scrubbed back into column 0 → snap back
        assertEquals(1, p.advance(0.7)) // forward again
    }

    // ── three+ columns ──────────────────────────────────────────────────────────────────────────

    @Test fun threeColumnSentenceTurnsAtEachBreak() {
        val p = NarratedColumnProgression(lead = 0.0)
        p.onSentence(listOf(0.4, 0.75, 1.0))
        assertNull(p.advance(0.1))
        assertEquals(1, p.advance(0.5))
        assertNull(p.advance(0.6))
        assertEquals(2, p.advance(0.8))
        assertNull(p.advance(0.95))
        assertEquals(0, p.advance(0.05)) // big seek back to the first column
    }

    // ── drift lead: bias slightly early ─────────────────────────────────────────────────────────

    @Test fun leadTurnsTheePageSlightlyEarly() {
        val noLead = NarratedColumnProgression(lead = 0.0)
        noLead.onSentence(listOf(0.6, 1.0))
        assertNull(noLead.advance(0.56)) // 0.56 < 0.60 → still column 0

        val lead = NarratedColumnProgression(lead = 0.06)
        lead.onSentence(listOf(0.6, 1.0))
        assertEquals(1, lead.advance(0.56)) // 0.56 + 0.06 ≥ 0.60 → turns early
    }

    // ── clamping ────────────────────────────────────────────────────────────────────────────────

    @Test fun fractionPastTheEndClampsToTheLastColumn() {
        val p = NarratedColumnProgression(lead = 0.0)
        p.onSentence(listOf(0.4, 0.75, 1.0))
        assertEquals(2, p.advance(1.5)) // overshoot (lead/rounding) never indexes past the last column
    }

    // ── reset / new sentence ────────────────────────────────────────────────────────────────────

    @Test fun onSentenceResetsToTheFirstColumn() {
        val p = NarratedColumnProgression(lead = 0.0)
        p.onSentence(listOf(0.6, 1.0))
        assertEquals(1, p.advance(0.9)) // end of sentence A on column 1
        p.onSentence(listOf(0.5, 1.0)) // sentence B begins — auto-follow has snapped to its column 0
        assertNull(p.advance(0.1))      // no spurious turn just because the previous one ended on column 1
        assertEquals(1, p.advance(0.6))
    }

    @Test fun resetForgetsTheCurrentSentence() {
        val p = NarratedColumnProgression(lead = 0.0)
        p.onSentence(listOf(0.6, 1.0))
        p.advance(0.9)
        p.reset()
        assertNull(p.advance(0.9)) // nothing measured → no turn
    }
}

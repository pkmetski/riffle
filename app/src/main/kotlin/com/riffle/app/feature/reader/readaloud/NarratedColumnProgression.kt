package com.riffle.app.feature.reader.readaloud

/**
 * Decides, as narration advances WITHIN a single sentence, which of the columns that sentence spans
 * should be on screen — and signals a page turn ONLY when that column changes.
 *
 * Read-aloud timing is per-sentence, not per-word: the audio clip maps to a whole sentence, so all we
 * know inside a sentence is the elapsed fraction of its clip. A sentence whose text wraps across a
 * paginated column boundary therefore has its tail stranded on the next page while the voice keeps
 * reading it. This maps that elapsed fraction onto the sentence's measured column layout and turns the
 * page at the estimated moment narration crosses each break.
 *
 * The columns are described by [onSentence] as a list of CUMULATIVE width fractions — `[0.6, 1.0]`
 * means 60% of the sentence's rendered width is in column 0 and the rest in column 1. Width is a
 * better proxy for narration time than character count (wider text takes longer to read), and it is
 * what the DOM hands us directly via `getClientRects()` (see ColumnSnap.measureNarratedColumns).
 *
 * Why "signal only on change" ([advance] returns null until the target column moves):
 *  - **Manual override** — between break points the target is constant, so a user who paged away
 *    mid-column is never yanked back on the next position poll; only a genuine break crossing re-asserts.
 *  - **Backward seek** — the target is recomputed from the live fraction every tick, so scrubbing back
 *    into an earlier column naturally turns back, with no special case.
 *
 * [lead] biases a drifting estimate slightly early (turn before the tail is spoken rather than strand
 * it briefly) — narration speed isn't perfectly uniform, and being a touch early is the lesser evil.
 *
 * Pure and synchronous so the page-turn decision is unit-testable in isolation; the geometry
 * (measuring the columns, performing the snap) lives in ColumnSnap.
 */
class NarratedColumnProgression(private val lead: Double = DEFAULT_LEAD) {

    // Cumulative width fractions, strictly increasing with last ≈ 1.0; size == number of columns the
    // current sentence spans. Empty or single-element ⇒ the sentence fits one column and never turns.
    private var boundaries: List<Double> = emptyList()

    // The column we last drove the page to, so [advance] can fire only on a change.
    private var lastColumn: Int = 0

    /**
     * Begin following a new sentence whose columns have cumulative width [fractions] (ascending, last
     * ≈ 1.0). An empty list (scroll mode, or a sentence not on this page) or a single column means the
     * sentence never turns. Resets the change-tracking — the caller's auto-follow has already placed
     * the page on the sentence's first column, so we start from column 0 and only turn from there.
     */
    fun onSentence(fractions: List<Double>) {
        boundaries = fractions
        lastColumn = 0
    }

    /** The column index that progress [fraction] (0..1 within the sentence) should display. */
    fun columnAt(fraction: Double): Int {
        val n = boundaries.size
        if (n <= 1) return 0
        var prev = 0.0
        for (i in 0 until n - 1) {
            // Turn into column i+1 slightly before narration reaches the break, to absorb estimate
            // drift. The lead is capped at half the column we're leaving so a column narrower than the
            // lead can't turn at its very start — otherwise a sentence beginning in the last sliver of
            // a page (a tiny first column) would jump off column 0 at fraction 0, fighting the
            // sentence-start snap.
            val effectiveLead = minOf(lead, (boundaries[i] - prev) / 2)
            if (fraction < boundaries[i] - effectiveLead) return i
            prev = boundaries[i]
        }
        return n - 1
    }

    /**
     * Feed a progress tick (elapsed [fraction] of the current sentence's clip). Returns the column to
     * snap to ONLY when it differs from the column we last drove to, else null — so ticks between
     * break points, and a manual page-turn, are left alone; a backward seek returns an earlier column.
     */
    fun advance(fraction: Double): Int? {
        val target = columnAt(fraction)
        if (target == lastColumn) return null
        lastColumn = target
        return target
    }

    /** Forget the current sentence (nothing measured) — e.g. when playback stops. */
    fun reset() {
        boundaries = emptyList()
        lastColumn = 0
    }

    companion object {
        /** Turn this fraction of the sentence early to absorb estimate drift, biasing toward "slightly early". */
        const val DEFAULT_LEAD = 0.06
    }
}

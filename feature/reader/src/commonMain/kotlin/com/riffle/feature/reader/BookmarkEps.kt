package com.riffle.feature.reader

import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.normalizeEpubHref

// The "does this bookmark fall on the page I am looking at?" window.
//
// Shared rather than per-platform because the same number drives two things that have to agree:
// the corner bookmark ribbon's lit state, and whether `toggleBookmark` deletes the existing
// bookmark or creates a second one. A host with its own epsilon would light the ribbon on pages
// it then refused to un-bookmark — which is precisely what a hardcoded iOS constant would have
// produced.

// Fallback ±5% within-chapter progression window for paginated / vertical modes when the
// spine position count for the current chapter isn't yet available (open-race). Once the
// count arrives, [bookmarkEpsFor] switches to `0.5 / positionsInChapter` — a strict
// ±half-a-page window, so the indicator only lights for the actual bookmarked page and
// never for its 3–4 neighbours (the "bookmark stays lit for way longer than expected"
// regression). A fixed 5% covered ~3 pages on typical (~60-position) chapters.
const val BOOKMARK_PAGE_EPS = 0.05

// Fallback ±33% for continuous mode when the spine position count for the current chapter
// isn't yet available (open-race). This is a conservative geometric cover: for a short
// chapter ~2 viewports tall, viewportFraction/2 approaches 0.3. Once the position count
// arrives, [bookmarkEpsFor] switches to the same `0.5 / positions` formula paginated uses
// — the locator emits viewport-midpoint progression in continuous mode, so the geometric
// minimum is viewportFraction/2, and viewportFraction ≈ 1/positionsInChapter (Readium
// sizes positions to viewport-page-equivalents). The old flat 33% caused the "bookmark
// stays lit for several screens" symptom in continuous just as the flat 5% did in
// paginated.
const val BOOKMARK_VIEWPORT_EPS = 0.33

/**
 * Pure decision for the ±progression window used to decide whether a bookmark falls on the
 * current viewport in [chapterHref].
 *
 * Priority (issue #399):
 *  1. **Live viewport-fraction** — `viewportSize / chapterSize` measured by the active
 *     renderer.
 *      - **Paginated / vertical:** `fraction / 2`. Readium's fragment-anchored `go()`
 *        lands the CFI's column / scrollY exactly, so the delta on arrival is 0 — a
 *        half-viewport window is the tightest correct bound.
 *      - **Continuous:** full `fraction`. The saved anchor could be anywhere in the
 *        viewport the user was reading in (top edge to bottom edge), so on an
 *        `alignToTop=true` bookmark-panel nav the arrival midpoint can drift by up to
 *        one full viewport-fraction from the saved midpoint. Tightening to `/ 2` would
 *        make the indicator flake off on arrival for any bookmark whose anchor sat in
 *        the lower half of the saved viewport — the user's principled contract is that
 *        this must never happen. One-viewport tolerance covers the anchor-position
 *        uncertainty exactly.
 *  2. **`0.5 / positionsInChapter`** / **`1.0 / positionsInChapter`** — Readium's
 *     positions are ~1024-char slices, a rough proxy. Same 2× continuous widening as
 *     above. Kicks in while the live measurement hasn't landed yet.
 *  3. **Flat [BOOKMARK_PAGE_EPS] / [BOOKMARK_VIEWPORT_EPS]** — final fallback for the
 *     open-race before positions arrive.
 */
fun bookmarkEpsFor(
    orientation: ReaderOrientation,
    spineCounts: Pair<List<String>, List<Int>>,
    viewportFractionByHref: Map<String, Double>,
    chapterHref: String,
): Double {
    val isContinuous = orientation == ReaderOrientation.Continuous
    viewportFractionByHref[chapterHref]?.let { vf ->
        if (vf > 0.0) return if (isContinuous) vf else vf / 2.0
    }
    val (hrefs, counts) = spineCounts
    val idx = hrefs.indexOfFirst { normalizeEpubHref(it) == chapterHref }
    val positions = counts.getOrNull(idx) ?: 0
    if (positions > 0) return if (isContinuous) 1.0 / positions else 0.5 / positions
    // Neither live fraction nor position count available — fall back to the
    // mode-appropriate flat eps.
    return if (isContinuous) BOOKMARK_VIEWPORT_EPS else BOOKMARK_PAGE_EPS
}

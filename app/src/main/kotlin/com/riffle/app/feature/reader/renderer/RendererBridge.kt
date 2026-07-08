package com.riffle.app.feature.reader.renderer

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/**
 * The single seam through which the paged/vertical EPUB reader talks to its WebView's JS world.
 *
 * Why a seam: before #331, ~20 `evaluateJavascript(` calls were sprayed across [EpubReaderScreen],
 * [ColumnSnap], [ReadiumPresenter], and the reserve/footnote helpers. There was no dependency
 * graph (the footnote bridge and selection tracker both need the rect polyfill, rediscovered
 * independently), no readiness model (re-install on reflow lived in scattered LaunchedEffects),
 * and no test surface (every script was a string evaluated against a live WebView). The hardest
 * reader bugs — highlight-after-rotation, decoration-positioning, reflow races — were all
 * symptoms of this missing seam.
 *
 * Shape: call sites are typed (`snapToEnd`, `applyReadaloudReserve`, `followNarratedSentence`,
 * …). The bridge owns the [RendererCapability] registry and the topo-sorted install order. A
 * recording fake implementation supports JVM unit tests for anything that drives the renderer.
 *
 * Out of scope: continuous mode (`ContinuousReaderView` / `ChapterWebView`) has its own WebView
 * pipeline (`ContinuousScriptInjector`, `ContinuousStyleInjector`) and is allowed to use
 * `evaluateJavascript(` directly; see the lint rule that enforces the rest.
 */
internal interface RendererBridge {

    /**
     * The set of capabilities this bridge knows about. Stable for the bridge's lifetime — the
     * declaration is the dependency graph.
     */
    val capabilities: List<RendererCapability>

    /**
     * Install every [CapabilityScope.PageLoad] capability in dependency order. Called from
     * `onPageLoaded` — each freshly served resource is a fresh document, so every capability
     * needs to re-install. All install scripts are idempotent.
     *
     * Returns the list of capability ids that were attempted, in install order. The tests assert
     * on this; callers can ignore it.
     */
    suspend fun installPageCapabilities(): List<CapabilityId>

    // ───── Typed call sites ────────────────────────────────────────────────────────────────────

    /**
     * Apply the targeted CSS overrides (font family, custom margins, …) to the live document
     * without waiting for the next page load. Called from `applyTypography` on the presenter when
     * the user changes a formatting preference.
     */
    suspend fun applyTypographyOverride()

    /**
     * Push the current readaloud-reserve height to the live document. Re-runs on every page load
     * (each new resource is a fresh document) AND whenever the reserve value changes mid-session
     * (a bundle finishes downloading; the user flips to/from scroll mode). Idempotent.
     */
    suspend fun applyReadaloudReserve(reservePx: Int)

    /**
     * Push the current figure-border rules (one CSS rule per annotated raster figure + one
     * fingerprint match per annotated inline `<svg>`, see
     * [com.riffle.app.feature.reader.decorations.FigureBorderDecoration]) to the live document.
     * Re-runs on every page load (fresh document) AND whenever the underlying annotation set
     * changes mid-session. Idempotent — replaces the whole rule set each call.
     */
    suspend fun applyFigureBorders(
        cssRules: List<String>,
        svgMatches: List<com.riffle.app.feature.reader.decorations.FigureBorderDecoration.SvgMatch> = emptyList(),
        rasterMarks: List<com.riffle.app.feature.reader.decorations.FigureBorderDecoration.RasterMark> = emptyList(),
    )

    // ── Column snapping ─────────────────────────────────────────────────────────────────────

    /** Install the at-rest snap backstop on a freshly loaded page (idempotent). */
    suspend fun installScrollSettleBackstop()

    /** Navigate to [link] then snap onto the target's column, tracking the post-load reflow. */
    suspend fun snapAfterGoTo(link: Link)

    /**
     * Navigate to [locator] then snap onto the target's column. [landAtStartWhenNoTarget] picks
     * the no-DOM-fragment landing (chapter top vs. round-to-grid for a within-chapter sync).
     */
    suspend fun snapAfterGoTo(locator: Locator, landAtStartWhenNoTarget: Boolean = true)

    /** Re-pin the current page to its LAST column through a backward cross-resource turn's reflow. */
    suspend fun snapToEnd()

    /**
     * Bring [fragmentId] onto the page (in-document cross-reference taps). Returns true iff the
     * snap moved the page — false when the target was already visible.
     */
    suspend fun snapToElement(fragmentId: String): Boolean

    /**
     * Cadence's variant of [snapToElement] that distinguishes "already visible" from "not in
     * this resource". Returns:
     *  - `"moved"` — snap changed the visible page,
     *  - `"same"` — target was already on-page, no action taken,
     *  - `"absent"` — id isn't in the currently-rendered DOM (caller navigates the cd's chapter),
     *  - `null` — no fragment attached.
     *
     * [snapToElement] collapses "same" and "absent" both to `false`, which is fine for taps
     * (that path only cares about "should we offer a Return card?"). For per-sentence auto-
     * follow we need the distinction: "same" ⇒ Snapped (leave the reader alone); "absent" ⇒
     * OffPage (load the other chapter, then re-run the snap on that page's load).
     */
    suspend fun snapCadenceSpan(fragmentId: String): String?

    /** True iff the freshly loaded paginated page is resting on its LAST column. */
    suspend fun landedAtEnd(): Boolean

    /**
     * Follow the narrated sentence to its column on a sentence change. Returns "on" (followed or
     * already on-page), "off" (sentence not on this resource — caller `go()`s to load it), or
     * null when the WebView is gone.
     */
    suspend fun followNarratedSentence(text: String): String?

    /** Measure how the narrated sentence is laid out across paginated columns. */
    suspend fun measureNarratedColumns(text: String): List<Double>

    /** Snap to the [columnIndex]-th column the narrated sentence occupies (clamped). */
    suspend fun snapNarratedColumn(text: String, columnIndex: Int)

    /** Id-based [measureNarratedColumns] for Cadence — resolves the target via `getElementById`. */
    suspend fun measureCadenceColumns(fragmentId: String): List<Double>

    /** Id-based [snapNarratedColumn] for Cadence — resolves the target via `getElementById`. */
    suspend fun snapCadenceColumn(fragmentId: String, columnIndex: Int)

    // ── Highlights-mode (elided reader) live DOM patches (ADR 0041) ───────────────────────────

    /**
     * Apply one [com.riffle.app.feature.reader.highlights.HighlightsDomPatch] to the currently
     * loaded elided-reader chapter. Each patch is a targeted `document.querySelector(...)`
     * mutation on the synthesised chapter's baked HTML — recolour a paragraph's accent bar, add/
     * update/remove a note aside, or delete a highlight paragraph — so the Annotations View
     * refreshes in place instead of the earlier full-Publication rebuild (which flashed through
     * Loading and unmounted the Readium fragment). Idempotent by design: an unrecognised
     * `data-ann-id` is a no-op, so it's safe to invoke against any chapter without checking
     * "is this the current one?" first.
     */
    suspend fun applyHighlightDomPatch(patch: com.riffle.app.feature.reader.highlights.HighlightsDomPatch)

    // ── Selection / readaloud probes ────────────────────────────────────────────────────────

    /**
     * Resolve the text-selection to a narrated-sentence id by GEOMETRY. Returns the span id or
     * null when nothing resolves (caller falls back to the stashed span id, then to chapter top).
     */
    suspend fun resolveSelectionSentence(sentences: List<Pair<String, String>>): String?

    /**
     * Read the selection-span id stashed by the selectionchange tracker. Used as a fallback when
     * [resolveSelectionSentence] doesn't resolve.
     */
    suspend fun readSelectionSpanId(): String?

    /** Returns the index of the first narrated sentence visible on the current page, or null. */
    suspend fun firstVisibleSentenceIndex(highlights: List<String>): Int?

    // ── Vertical (scroll) mode ──────────────────────────────────────────────────────────────

    /**
     * `window.scrollBy(0, delta)`. Returns true iff scrollY changed, false iff it didn't move
     * (e.g. parked at the document boundary), or null when the WebView is gone — callers should
     * skip the iteration in the null case rather than treat it as "stuck at end".
     */
    suspend fun scrollByPx(delta: Int): Boolean?

    /**
     * Read whether the live document is at its forward / backward scroll boundary. The result is
     * `(atForward, atBackward)`. Mapped to the presenter-layer `ScrollBoundary` data class by the
     * presenter; the bridge stays free of presenter-layer types.
     */
    suspend fun scrollBoundary(): Pair<Boolean, Boolean>

    /**
     * Measure `viewportSize / scrollSize` for the currently loaded resource. Paginated mode
     * overflows horizontally (`innerWidth / scrollWidth`); vertical mode overflows vertically
     * (`innerHeight / scrollHeight`). The JS picks the axis by whichever dimension actually
     * overflows the viewport. Returns null when the WebView is gone or the measurement can't
     * be parsed; caller (presenter) skips publishing in that case.
     *
     * Feeds `BookmarksController` via `EpubReaderViewModel.viewportFractionByHref` (issue #399).
     * The JS reads no scroll state — safe to call from a page-load or typography-change hook.
     */
    suspend fun readViewportFraction(): Double?

    /**
     * Evaluate a script supplied by `ScrollBoundaryNavigationContainer` for the volume-key
     * boundary crossing. The container builds the smooth-scroll JS itself (deciding by orientation
     * + boundary state) and just needs a way to send it to the fragment. The script is fully owned
     * by the container; the bridge merely forwards it so the lint rule can keep
     * `evaluateJavascript(` confined to this package.
     */
    suspend fun evaluateBoundaryScroll(js: String)

    // ── Cadence (issue #403) ───────────────────────────────────────────────────────────────────

    /**
     * Probe the paged/vertical WebView for `Intl.Segmenter`. Returns `"true"` / `"false"` (raw
     * JSON), or null when the fragment is gone. The reader screen uses this to gate the Cadence
     * top-bar toggle: no `Intl.Segmenter` → toggle hidden, feature disabled.
     */
    suspend fun evaluateCadenceFeatureDetect(): String?

    /**
     * Run Cadence's DOM tokenisation script for [chapterHref] in the paged/vertical WebView and
     * return the raw JSON string it produces. See
     * [com.riffle.app.feature.reader.cadence.CadenceDomScript.tokeniseChapterJs] for the payload
     * shape and [com.riffle.app.feature.reader.cadence.CadenceInjector.parse] for how the reader
     * consumes it. Null when the fragment is gone.
     */
    suspend fun evaluateCadenceTokenise(chapterHref: String, localeTag: String?): String?

    /**
     * Return the id of the `<span class="riffle-cd">` Cadence should START from (e.g. `"cd-7"`).
     *
     * The choice is section-aware — prefers the first `h1..h6` heading visible in the viewport
     * (its opening `.riffle-cd`), then the nearest preceding heading, then the first
     * `.riffle-cd` currently on-screen. See
     * [com.riffle.app.feature.reader.cadence.CadenceDomScript.CADENCE_START_SPAN_ID_JS] for the
     * exact JS contract. Returns null when the fragment is gone or no fallback matches.
     */
    suspend fun cadenceStartSpanId(): String?
}

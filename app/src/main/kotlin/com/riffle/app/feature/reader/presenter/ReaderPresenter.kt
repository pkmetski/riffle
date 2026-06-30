package com.riffle.app.feature.reader.presenter

import com.riffle.core.domain.FormattingPreferences
import kotlinx.coroutines.flow.Flow

/**
 * The seam between the reader UI (screen + view-model) and the concrete rendering pipeline.
 * Two adapters today: [ReadiumPresenter] (paginated + vertical via Readium's
 * [org.readium.r2.navigator.epub.EpubNavigatorFragment]) and [ContinuousPresenter] (the custom
 * endless-scroll renderer). Both implement this interface; the view-model and any future
 * orchestrators depend only on the interface — they MUST NOT import Readium or
 * [com.riffle.app.feature.reader.ContinuousReaderView] types directly.
 *
 * Mode-specific concerns (column snap, fragment lifecycle, sliding-window position translation)
 * live behind the adapters. Issue #300 routes decoration application, navigation, typography,
 * volume-key paging, and continuous-mode position events through this seam. Higher-level
 * concerns — three-peer progress sync, readaloud highlight orchestration, annotation merge —
 * sit upstream of the seam in orchestrators that subscribe to its event flows.
 */
internal interface ReaderPresenter {

    // ----- Events the view-model observes ----------------------------------------------------

    /** A position change reported by the underlying renderer. Idempotent on the same position. */
    val positionEvents: Flow<PositionUpdate>

    /**
     * Bumps every time the renderer reports "layout settled" (Readium's
     * [org.readium.r2.navigator.epub.EpubNavigatorFragment.PaginationListener.onPageLoaded] for
     * paginated/vertical, the equivalent web-view ready signal for continuous). Consumers re-key
     * decoration application on this.
     */
    val pageLoadEvents: Flow<PageLoadGeneration>

    /** Any user tap that should toggle reader chrome (left/right tap zones, body tap). */
    val tapEvents: Flow<TapEvent>

    /** Internal link, external link, or footnote anchor user activation. */
    val linkEvents: Flow<LinkEvent>

    /** A "highlight" or "play-from-here" action invoked from the text-selection menu. */
    val selectionEvents: Flow<SelectionEvent>

    /** User tapped an existing annotation highlight or its note glyph. */
    val annotationTapEvents: Flow<AnnotationTapEvent>

    // ----- Commands the view-model issues ----------------------------------------------------

    /** Navigate to a Locator JSON, an href + optional fragment, or a progression within a href. */
    suspend fun navigateTo(target: NavigationTarget, options: NavigationOptions = NavigationOptions())

    /**
     * Apply (or re-apply) typography for the current rendering preferences. Idempotent on the
     * same preferences. The adapter decides what to inject and when (e.g. on next page load).
     */
    suspend fun applyTypography(prefs: FormattingPreferences)

    /** Latest position the renderer has reported, or `null` if it has not reported any yet. */
    fun snapshotPosition(): ReaderPosition?

    /** Page forward / page backward (volume keys, configurable gestures). */
    suspend fun pageBy(direction: PageDirection)

    // ----- Readaloud sentence follow (paginated only — vertical / continuous return Unavailable) -

    /**
     * Locate the narrated sentence [text] on the current page and, if found, snap to the column
     * holding its start. Drives the per-sentence highlight follow.
     *
     * Implementations:
     * - Paginated (Readium): runs the column-snap probe inside the fragment WebView.
     * - Vertical / continuous: return [ReadaloudFollowResult.Unavailable]; their follow pipelines
     *   live elsewhere (vertical = native scroll; continuous = JS injection in
     *   `ContinuousReaderView`).
     */
    suspend fun followReadaloudSentence(text: String): ReadaloudFollowResult

    /**
     * Measure how [text] spans the paginated column grid; non-empty only in paginated mode.
     * Empty means "single column / not on this resource / vertical / continuous" — all cases
     * where intra-sentence page turns should not be driven by [NarratedColumnProgression].
     */
    suspend fun measureReadaloudColumns(text: String): List<Double>

    /**
     * Snap the page to the [columnIndex]-th column the narrated [text] spans. The companion to
     * [measureReadaloudColumns]. No-op in vertical / continuous.
     */
    suspend fun snapReadaloudColumn(text: String, columnIndex: Int)

    /**
     * Current native-scroll boundary state for the rendered content. Used by the vertical-mode
     * chapter-boundary gesture (ADR 0014) and by volume-key navigation to decide whether the
     * next page-step should turn the chapter or scroll within it.
     *
     * Paginated mode (column pagination, no native scroll) always reports `(false, false)`.
     * Continuous mode owns its own boundary handling inside `ContinuousReaderView`; this
     * snapshot is informational only there.
     */
    suspend fun scrollBoundary(): ScrollBoundary
}

/** Snapshot of native-scroll boundary state — both flags are independent. */
internal data class ScrollBoundary(
    val atForwardBoundary: Boolean,
    val atBackwardBoundary: Boolean,
) {
    companion object {
        val None = ScrollBoundary(atForwardBoundary = false, atBackwardBoundary = false)
    }
}

/** Outcome of [ReaderPresenter.followReadaloudSentence]. */
internal enum class ReadaloudFollowResult {
    /** The sentence was located on the current resource and the page snapped to its column. */
    Snapped,
    /** The sentence is not on the currently-rendered resource — caller falls back to navigation. */
    OffPage,
    /** This presenter does not drive per-sentence follow (vertical scroll / continuous mode). */
    Unavailable,
}

// =================== Event payloads ==========================================================

/**
 * The minimal position carried across the seam. The Readium Locator's full JSON is kept as
 * an opaque round-trip token so the storage layer (which still serialises Locator JSON into
 * `reading_positions.cfi`) survives the cutover without schema changes.
 *
 * @property locatorJson the underlying Readium Locator serialised as JSON, or empty in tests.
 */
internal data class ReaderPosition(
    val href: String,
    val progression: Float,
    val totalProgression: Float?,
    val locatorJson: String,
)

internal data class PositionUpdate(
    val position: ReaderPosition,
    /** Increments every time the position changes; consumers can debounce/throttle on it. */
    val generation: Long,
)

internal data class PageLoadGeneration(val value: Int)

internal sealed class TapEvent {
    /** Any tap not on a link/selection control — toggle chrome. */
    data object Body : TapEvent()
}

internal sealed class LinkEvent {
    /** A cross-resource link tap; [origin] is the position the user was at when they tapped. */
    data class InternalLink(val href: String, val originLocatorJson: String) : LinkEvent()

    data class ExternalLink(val url: String) : LinkEvent()

    /** A footnote anchor tap; [contentHtml] is the resolved footnote body, ready for popup. */
    data class Footnote(val contentHtml: String) : LinkEvent()
}

internal sealed class SelectionEvent {
    data class HighlightRequest(
        val href: String,
        val text: String,
        val progression: Float,
        val before: String?,
        val after: String?,
    ) : SelectionEvent()

    data class PlayFromHereRequest(
        val href: String,
        val text: String,
        /** Optional JS to evaluate against the current page for further context resolution. */
        val resolverJs: String? = null,
    ) : SelectionEvent()
}

internal sealed class AnnotationTapEvent {
    data class Highlight(val href: String, val annotationId: String) : AnnotationTapEvent()
    data class NoteGlyph(val href: String, val annotationId: String) : AnnotationTapEvent()
}

// =================== Command payloads ========================================================

/**
 * Per-navigation policy options. Defaults match a tap-from-TOC (snap to the landing column, land
 * at the chapter top when no anchor, animate the page turn). Override per call-site:
 *
 * - **Server-progress resume / annotation jump**: [landAtStartWhenNoTarget] = `false` — don't yank
 *   to the chapter top; honour the locator's progression.
 * - **Readaloud `play-from-here`**: [snap] = `false`, [animated] = `false` — the locator already
 *   names the precise sentence column; snap would round it off.
 * - **Annotation jump in continuous mode**: [alignToTop] = `true` — bookmark progressions are
 *   content-top-relative, not viewport-midpoint (the inverse [locatorAt] uses).
 *
 * Honoured by:
 * - [ReadiumPresenter]: [snap], [landAtStartWhenNoTarget], [animated]. [alignToTop] is irrelevant
 *   (Readium handles column alignment internally).
 * - [ContinuousPresenter]: [alignToTop]. The other flags do not apply (no column grid, no
 *   Readium-go animation control).
 */
internal data class NavigationOptions(
    /** Readium-only. Run [ColumnSnap.goAndSnap] after `go()` to round the page to the column grid. */
    val snap: Boolean = true,
    /**
     * Readium-only. When the target locator carries no DOM anchor (no `#fragment`), `true` lands
     * at the chapter top; `false` honours the locator's progression. Ignored in continuous mode
     * (continuous always honours the explicit progression).
     */
    val landAtStartWhenNoTarget: Boolean = true,
    /**
     * Readium-only. Whether the page-turn animates. Only consulted on the non-snap branch — the
     * snap branch's animation is owned by [ColumnSnap.goAndSnap].
     */
    val animated: Boolean = true,
    /**
     * Continuous-only. `true` for content-top-relative progressions (CFI-derived bookmarks);
     * `false` for viewport-midpoint progressions (the inverse [locatorAt] uses). Ignored by
     * Readium (it handles column alignment internally).
     */
    val alignToTop: Boolean = false,
)

internal sealed class NavigationTarget {
    /** Resume to a previously persisted Readium Locator (verbatim JSON). */
    data class ToLocatorJson(val locatorJson: String) : NavigationTarget()

    /** Jump to a chapter and optional intra-document anchor (TOC, internal link). */
    data class ToHref(val href: String, val fragment: String? = null) : NavigationTarget()

    /** Jump to a relative offset within a chapter (server-progress restore, search match). */
    data class ToProgression(val href: String, val progression: Float) : NavigationTarget()
}

internal enum class PageDirection { Forward, Backward }

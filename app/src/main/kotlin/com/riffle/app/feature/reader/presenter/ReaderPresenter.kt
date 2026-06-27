package com.riffle.app.feature.reader.presenter

import com.riffle.core.domain.FormattingPreferences
import kotlinx.coroutines.flow.Flow

/**
 * The seam between the reader UI (screen + view-model) and the concrete rendering pipeline
 * (Readium [org.readium.r2.navigator.epub.EpubNavigatorFragment] for paginated/vertical modes,
 * or the custom [com.riffle.app.feature.reader.ContinuousReaderView] for continuous mode).
 *
 * The view-model and screen depend on this interface; they MUST NOT import Readium types
 * directly. Mode-specific concerns — column snap, fragment lifecycle, manual scroll-to-Locator
 * translation — live behind one of the adapters.
 *
 * **Step 1 scope (issue #300).** Only [ReadiumPresenter] exists today; the screen still mounts
 * Readium fragments and the continuous view directly. Decoration application stays on the
 * existing [com.riffle.app.feature.reader.HighlightRenderer] for now and will move behind this
 * seam in a follow-up cutover step.
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
    suspend fun navigateTo(target: NavigationTarget)

    /**
     * Apply (or re-apply) typography for the current rendering preferences. Idempotent on the
     * same preferences. The adapter decides what to inject and when (e.g. on next page load).
     */
    suspend fun applyTypography(prefs: FormattingPreferences)

    /** Latest position the renderer has reported, or `null` if it has not reported any yet. */
    fun snapshotPosition(): ReaderPosition?

    /** Page forward / page backward (volume keys, configurable gestures). */
    suspend fun pageBy(direction: PageDirection)
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

internal sealed class NavigationTarget {
    /** Resume to a previously persisted Readium Locator (verbatim JSON). */
    data class ToLocatorJson(val locatorJson: String) : NavigationTarget()

    /** Jump to a chapter and optional intra-document anchor (TOC, internal link). */
    data class ToHref(val href: String, val fragment: String? = null) : NavigationTarget()

    /** Jump to a relative offset within a chapter (server-progress restore, search match). */
    data class ToProgression(val href: String, val progression: Float) : NavigationTarget()
}

internal enum class PageDirection { Forward, Backward }

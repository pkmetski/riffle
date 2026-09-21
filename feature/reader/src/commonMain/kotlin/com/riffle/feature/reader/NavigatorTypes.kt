package com.riffle.feature.reader

import com.riffle.core.models.EmphasisStyle

/**
 * Readium Locator serialised to JSON — the platform-neutral position currency used throughout
 * the reader. Readium Android produces these via `Locator.toJSON().toString()`; the iOS
 * Readium Swift SDK serialises identically. Opaque to commonMain; adapters convert at the
 * platform boundary.
 */
typealias LocatorJson = String

/** Platform-neutral position snapshot carried across the navigator seam. */
data class NavigatorPosition(
    val href: String,
    val progression: Float,
    val totalProgression: Float?,
    /** Round-trip Locator JSON; empty only in tests that don't need serialisation. */
    val locatorJson: LocatorJson,
)

/** A visual decoration the navigator should render over the book content. */
sealed class NavigatorDecoration {
    abstract val id: String

    /** A coloured highlight range. */
    data class Highlight(
        override val id: String,
        val locatorJson: LocatorJson,
        /** Hex colour string, e.g. `"#FFFF00"`. */
        val color: String,
        val alpha: Float = 0.4f,
    ) : NavigatorDecoration()

    /** A note glyph anchor (no highlight; glyph rendered at the anchor position). */
    data class NoteGlyph(
        override val id: String,
        val locatorJson: LocatorJson,
    ) : NavigatorDecoration()

    /** A search result highlight (distinct style from user highlights). */
    data class SearchMark(
        override val id: String,
        val locatorJson: LocatorJson,
        val isCurrent: Boolean = false,
    ) : NavigatorDecoration()

    /** A bookmark indicator. */
    data class Bookmark(
        override val id: String,
        val locatorJson: LocatorJson,
    ) : NavigatorDecoration()

    /**
     * A `TYPE_EMPHASIS` range (ADR 0056) rendered as a decoration.
     *
     * Only [EmphasisStyle.UNDERLINE] and [EmphasisStyle.STRIKE] belong here: they are drawn
     * *over* the text and do not change its metrics. [EmphasisStyle.BOLD] and
     * [EmphasisStyle.ITALIC] reflow the line, so both platforms apply them by mutating the DOM
     * before the navigator measures decoration rects — Android via
     * `EmphasisDomInjector`, iOS via the same shared script through the JS seam. Sending them
     * here would silently paint nothing.
     *
     * [styles] may carry the full set; the renderer picks out the two it can draw.
     */
    data class Emphasis(
        override val id: String,
        val locatorJson: LocatorJson,
        val styles: Set<EmphasisStyle>,
    ) : NavigatorDecoration()
}

/** Events the navigator emits in response to user interaction. */
sealed class NavigatorEvent {
    /** Any tap that is not on a link or selection control — used to toggle chrome. */
    data object BodyTap : NavigatorEvent()

    /** User activated an internal link. */
    data class InternalLink(val href: String, val originLocatorJson: LocatorJson) : NavigatorEvent()

    /** User activated an external link. */
    data class ExternalLink(val url: String) : NavigatorEvent()

    /** A footnote anchor was tapped; [contentHtml] is the resolved body. */
    data class Footnote(val contentHtml: String) : NavigatorEvent()

    /** User selected text and requested a highlight. */
    data class HighlightRequest(
        val href: String,
        val text: String,
        val progression: Float,
        val before: String?,
        val after: String?,
    ) : NavigatorEvent()

    /** User selected text and requested "play from here". */
    data class PlayFromHereRequest(
        val href: String,
        val text: String,
        val resolverJs: String? = null,
    ) : NavigatorEvent()

    /** User tapped an existing annotation highlight. */
    data class AnnotationHighlightTap(val href: String, val annotationId: String) : NavigatorEvent()

    /** User tapped an annotation note glyph. */
    data class AnnotationGlyphTap(val href: String, val annotationId: String) : NavigatorEvent()
}

/**
 * A rectangle in the navigator view's own coordinate space, in density-independent units.
 *
 * Carried so the host can anchor a popup next to what the user touched. Android already passes
 * an `IntRect` through `onHighlight` / `onDecorationActivated`; this is the platform-neutral
 * shape of the same thing.
 */
data class NavigatorRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * A live text selection in the rendered publication.
 *
 * [text] / [before] / [after] are Readium's `Locator.Text` triple — the text-quote anchor the
 * whole annotation domain is built on. [progression] is the *page's* progression, not the
 * selection's: both Readium implementations report the visible page's position and attach the
 * selected text to it, which is why `buildHighlightAnchor` re-derives the true within-chapter
 * position from the chapter HTML instead of trusting this value.
 */
data class NavigatorSelection(
    val locatorJson: LocatorJson,
    val href: String,
    val text: String,
    val before: String,
    val after: String,
    val progression: Double,
    val rect: NavigatorRect?,
)

/** The user tapped a rendered decoration. [id] is the annotation id, [group] its decoration group. */
data class NavigatorDecorationActivation(
    val id: String,
    val group: String,
    val rect: NavigatorRect?,
)

/** A navigator page-turn direction. */
enum class NavigatorPageDirection { Forward, Backward }

/** Navigation targets the ViewModel issues to the navigator. */
sealed class NavigatorNavigationTarget {
    /** Navigate to a persisted Readium Locator JSON (verbatim round-trip). */
    data class ToLocatorJson(val locatorJson: LocatorJson) : NavigatorNavigationTarget()
    /** Navigate to a chapter href with an optional intra-doc anchor. */
    data class ToHref(val href: String, val fragment: String? = null) : NavigatorNavigationTarget()
    /** Navigate to a relative progression within a chapter. */
    data class ToProgression(val href: String, val progression: Float) : NavigatorNavigationTarget()
}

/** Per-navigation policy. Mirrors `:app`'s `NavigationOptions`; kept simple here. */
data class NavigatorNavigationOptions(
    val snap: Boolean = true,
    val landAtStartWhenNoTarget: Boolean = true,
    val snapProgressionToNearestColumn: Boolean = false,
    val animated: Boolean = true,
    val alignToTop: Boolean = false,
    val focusAnnotationId: String? = null,
)

/** Bumps every time the renderer finishes loading a chapter and layout has settled. */
data class NavigatorPageLoad(val generation: Int)

/** Platform-agnostic search result for a single text match. */
data class NavigatorSearchMatch(
    val locatorJson: LocatorJson,
    val snippet: String,
)

/** Scroll boundary state (for vertical / continuous chapter-boundary gestures). */
data class NavigatorScrollBoundary(
    val atForwardBoundary: Boolean,
    val atBackwardBoundary: Boolean,
) {
    companion object {
        val None = NavigatorScrollBoundary(atForwardBoundary = false, atBackwardBoundary = false)
    }
}

/** Result of a readaloud sentence-follow attempt. */
enum class NavigatorFollowResult { Snapped, OffPage, Unavailable }

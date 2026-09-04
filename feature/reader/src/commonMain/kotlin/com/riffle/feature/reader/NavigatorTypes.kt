package com.riffle.feature.reader

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

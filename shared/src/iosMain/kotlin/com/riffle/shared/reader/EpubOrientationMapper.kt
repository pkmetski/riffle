package com.riffle.shared.reader

import com.riffle.core.domain.ReaderOrientation
import com.riffle.feature.reader.highlights.ReaderSource

/**
 * Maps a [ReaderOrientation] to the Readium `scroll` preference (true = vertical scroll,
 * false = paginated columns).
 *
 * Mirrors Android's FormattingPreferencesMapper: only [ReaderOrientation.Horizontal] maps to
 * paginated. Both [ReaderOrientation.Vertical] and [ReaderOrientation.Continuous] use scroll
 * mode so that continuous mode is never silently rendered as paginated columns.
 */
fun epubScrollMode(orientation: ReaderOrientation): Boolean =
    orientation != ReaderOrientation.Horizontal

/**
 * Whether to activate [IosContinuousScrollView] for [source] + [orientation].
 *
 * Always `false` for [ReaderSource.Highlights]: the synthetic EPUB's accent-bar tap elements
 * navigate to `riffle://annotation-tap/<id>` URLs. [IosContinuousScrollView] has no
 * `decidePolicyFor` navigation delegate and therefore no `riffle://` interceptor — the URLs
 * are silently swallowed and the [AnnotationActionsSheet] never opens. Use
 * [EPUBNavigatorViewController] (isContinuousMode = false) unconditionally in that mode so
 * [presentExternalURL] fires correctly.
 */
fun elidedAwareIsContinuousMode(source: ReaderSource, orientation: ReaderOrientation): Boolean =
    source != ReaderSource.Highlights && orientation == ReaderOrientation.Continuous

/**
 * Readium `scroll` setting for [source] + [orientation].
 *
 * Highlights mode always uses vertical scroll so the annotation list is displayed as a
 * single scrollable page regardless of the user's global reading mode preference.
 */
fun elidedAwareScrollMode(source: ReaderSource, orientation: ReaderOrientation): Boolean =
    if (source == ReaderSource.Highlights) true else epubScrollMode(orientation)

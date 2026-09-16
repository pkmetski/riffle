package com.riffle.shared.reader

import com.riffle.core.domain.ReaderOrientation

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

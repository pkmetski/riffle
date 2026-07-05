package com.riffle.app.feature.reader.highlights

/**
 * UI-visibility decision points for Highlights mode (Task 9, ADR 0041). Extracted as pure
 * top-level functions — not test-only mirrors — so [EpubReaderScreen][com.riffle.app.feature.reader.EpubReaderScreen]
 * can call them directly at each branch point, and so the decisions are JVM-testable without
 * constructing the ViewModel (Robolectric-only constraint documented in Task 7) or Compose runtime.
 */

/** Readaloud entry points (top-bar toggle, mini-player, download dialog) only make sense against
 *  the real ABS EPUB — the elided Highlights-mode Publication has no matched audiobook. */
internal fun shouldShowReadaloudUi(source: ReaderSource): Boolean = source == ReaderSource.FullBook

/** The chapter rail needs subchapter resolution against the real book's reading order, which the
 *  synthesised Highlights-mode Publication doesn't have. */
internal fun shouldShowChapterRail(source: ReaderSource): Boolean = source == ReaderSource.FullBook

/** "Open in book" is the escape hatch out of the elided reader back to the full book; it has no
 *  meaning when already reading the full book. */
internal fun shouldShowOpenInBook(source: ReaderSource): Boolean = source == ReaderSource.Highlights

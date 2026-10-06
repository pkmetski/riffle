package com.riffle.feature.reader.highlights

/** Returns true when the readaloud rail/button should be visible for [source]. */
fun shouldShowReadaloudUi(source: ReaderSource): Boolean = source == ReaderSource.FullBook

/** Returns true when the "Open in book" action should be offered for [source]. */
fun shouldShowOpenInBook(source: ReaderSource): Boolean = source == ReaderSource.Highlights

/** Returns true when the "Share as PDF" action should be offered for [source]. */
fun shouldShowShareHighlights(source: ReaderSource): Boolean = source == ReaderSource.Highlights

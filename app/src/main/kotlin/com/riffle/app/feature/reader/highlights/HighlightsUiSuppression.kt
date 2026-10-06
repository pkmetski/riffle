package com.riffle.app.feature.reader.highlights

internal fun shouldShowReadaloudUi(source: ReaderSource): Boolean =
    com.riffle.feature.reader.highlights.shouldShowReadaloudUi(source)

internal fun shouldShowOpenInBook(source: ReaderSource): Boolean =
    com.riffle.feature.reader.highlights.shouldShowOpenInBook(source)

internal fun shouldShowShareHighlights(source: ReaderSource): Boolean =
    com.riffle.feature.reader.highlights.shouldShowShareHighlights(source)

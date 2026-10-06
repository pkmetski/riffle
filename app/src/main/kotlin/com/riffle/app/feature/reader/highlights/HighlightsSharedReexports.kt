package com.riffle.app.feature.reader.highlights

/**
 * Re-exports of symbols moved to `feature:reader` commonMain. These typealiases/properties
 * preserve the `com.riffle.app.feature.reader.highlights.*` name for existing `:app` call sites
 * so each consumer doesn't need a mechanical import update.
 */

typealias ChapterElision = com.riffle.feature.reader.highlights.ChapterElision

val FALLBACK_ORIGIN_FONT_FAMILY: String
    get() = com.riffle.feature.reader.FALLBACK_ORIGIN_FONT_FAMILY

val ACCENT_BAR_TAP_CLASS: String
    get() = com.riffle.feature.reader.highlights.ACCENT_BAR_TAP_CLASS

val ELIDED_NOTE_LABEL: String
    get() = com.riffle.feature.reader.highlights.ELIDED_NOTE_LABEL

val ELIDED_NOTE_ARIA_LABEL: String
    get() = com.riffle.feature.reader.highlights.ELIDED_NOTE_ARIA_LABEL

val EMPHASIS_ONLY_BAR_COLOR: String
    get() = com.riffle.feature.reader.highlights.EMPHASIS_ONLY_BAR_COLOR

fun realCapturedFontOrNull(value: String?): String? =
    com.riffle.feature.reader.highlights.realCapturedFontOrNull(value)

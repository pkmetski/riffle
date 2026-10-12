package com.riffle.shared.reader

import com.riffle.core.domain.SentenceQuote
import com.riffle.core.models.HighlightColor
import com.riffle.feature.reader.NavigatorDecoration
import com.riffle.feature.reader.sentenceLocatorJson

/** Readium decoration group for the readaloud "currently narrated" sentence. */
internal const val DECORATION_GROUP_READALOUD = "readaloud"

/** Decoration id — constant so the group replaces atomically on each sentence change. */
private const val READALOUD_DECORATION_ID = "readaloud_active"

/**
 * Highlight decoration for the sentence being narrated by readaloud.
 *
 * Mirrors [cadenceDecoration] in structure: [sentenceLocatorJson] produces the TextQuoteAnchor
 * that survives Readium's id-stripping when serving the EPUB, and [HighlightColor.argb] carries
 * the baked-in 0x80 alpha so the tint matches the user-chosen colour swatch exactly.
 */
internal fun readaloudDecoration(
    fragmentRef: String,
    quote: SentenceQuote?,
    color: HighlightColor,
): NavigatorDecoration.Highlight = NavigatorDecoration.Highlight(
    id = READALOUD_DECORATION_ID,
    locatorJson = sentenceLocatorJson(fragmentRef, quote),
    color = highlightColorHex(color.token),
    alpha = highlightColorAlpha(color.token),
)

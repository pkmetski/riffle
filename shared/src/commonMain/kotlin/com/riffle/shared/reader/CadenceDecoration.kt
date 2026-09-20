package com.riffle.shared.reader

import com.riffle.core.domain.SentenceQuote
import com.riffle.core.models.HighlightColor
import com.riffle.feature.reader.NavigatorDecoration
import com.riffle.feature.reader.sentenceLocatorJson

/** Readium decoration group for Cadence's "currently reading" sentence. */
internal const val DECORATION_GROUP_CADENCE = "cadence"

/**
 * The single decoration id Cadence ever applies. Replacing the whole group on each sentence is
 * what makes the highlight move rather than accumulate, so the id is constant by design.
 */
internal const val CADENCE_DECORATION_ID = "cadence_active"

/**
 * Build the "currently reading" highlight for Cadence's `href#cd-N` fragment.
 *
 * The locator comes from `feature:reader`'s [sentenceLocatorJson], the same derivation Android's
 * `readaloudLocatorJson` wraps, so both platforms anchor the sentence the same way: the injected
 * `cd-N` span's cssSelector first, with the sentence text as a TextQuoteAnchor fallback.
 *
 * The colour resolves through [HighlightColor] rather than a hex literal. Passing the stored
 * token straight to the Swift bridge is exactly the bug that made every iOS annotation highlight
 * render black (#1071 §6) — `Scanner.scanHexInt64` cannot read `"yellow"` and yields rgb = 0 —
 * and [HighlightColor.argb] carries the baked-in 0x80 alpha, so the sentence highlight is the
 * same pixel value as the swatch in the Settings picker.
 */
internal fun cadenceDecoration(
    fragmentRef: String,
    quote: SentenceQuote?,
    color: HighlightColor,
): NavigatorDecoration.Highlight = NavigatorDecoration.Highlight(
    id = CADENCE_DECORATION_ID,
    locatorJson = sentenceLocatorJson(fragmentRef, quote),
    color = highlightColorHex(color.token),
    alpha = highlightColorAlpha(color.token),
)

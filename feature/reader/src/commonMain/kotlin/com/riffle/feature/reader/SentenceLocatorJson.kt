package com.riffle.feature.reader

import com.riffle.core.domain.SentenceQuote
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The Readium `Locator.type` every reflowable EPUB resource carries. */
const val SENTENCE_LOCATOR_TYPE: String = "application/xhtml+xml"

/**
 * The Readium Locator JSON for a sentence fragment ref — `"href#fragmentId"`, or a bare `"href"`
 * when the ref carries no fragment.
 *
 * Used by both the Readaloud "now speaking" decoration and Cadence's per-sentence highlight, on
 * both platforms. Readium's decoration positioner resolves `locations.cssSelector` when the span
 * survives into the rendered DOM (always true for Cadence, whose `cd-N` spans the tokeniser
 * itself injected) and otherwise falls back to a TextQuoteAnchor search over the body — which is
 * why a [quote], when known, MUST reach the locator as a `text` block. Dropping it is how a
 * Readaloud highlight vanishes on publications that strip media-overlay spans.
 *
 * Android's `readaloudLocatorJson` parses this string into an `org.json.JSONObject`; iOS hands it
 * straight to the Swift bridge, which feeds it to `Locator(json:)`. One derivation, so the two
 * platforms cannot anchor the same sentence differently.
 */
fun sentenceLocatorJson(ref: String, quote: SentenceQuote? = null): String {
    val hashIdx = ref.indexOf('#')
    val href = if (hashIdx >= 0) ref.substring(0, hashIdx) else ref
    val fragId = if (hashIdx >= 0) ref.substring(hashIdx + 1) else null
    return buildJsonObject {
        put("href", href)
        put("type", SENTENCE_LOCATOR_TYPE)
        putJsonObject("locations") {
            if (fragId != null) {
                put("fragments", JsonArray(listOf(JsonPrimitive(fragId))))
                put("cssSelector", "#$fragId")
            }
        }
        if (quote != null) {
            putJsonObject("text") {
                put("before", quote.before)
                put("highlight", quote.highlight)
                put("after", quote.after)
            }
        }
    }.toString()
}

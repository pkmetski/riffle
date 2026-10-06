package com.riffle.feature.reader

/**
 * Cross-platform PDF locator codec.
 *
 * Readium's `Locator.toJSON()` uses **1-based** `locations.position` (page 1 = first page).
 * PDFKit and PdfRenderer use **0-based** page indices natively. This codec converts between the
 * two so a locator written by iOS and read by Android (via `Locator.fromJSON`) lands on the same
 * page, and vice versa.
 *
 * Format evolution:
 * - Old iOS (pre-codec): 0-based position stored as `{"href":"/page-0","locations":{"position":0}}`.
 *   No `"__v"` field. Detected by absence of `"__v":2`.
 * - New shared (this codec): 1-based position, `"__v":2` marker so decode can distinguish it from
 *   the old format. `"__v"` is a private Riffle extension field; Readium ignores unknown fields.
 * - Android / Readium: `Locator.toJSON()` — 1-based, no `"__v"`. Distinguished from old iOS format
 *   by href not matching `/page-N` (real PDF resource href).
 */
object PdfLocatorCodec {

    private const val VERSION = 2
    private const val VERSION_KEY = "\"__v\":$VERSION"

    /**
     * Encode a 0-based [page] index and [pageCount] into a Readium-compatible locator JSON string
     * with a version marker so [decode0Based] can round-trip it reliably.
     */
    fun encode(page0Based: Int, pageCount: Int): String {
        val pos1Based = page0Based + 1
        val progression = if (pageCount > 0) page0Based.toDouble() / pageCount else 0.0
        return """{"href":"/page-$pos1Based","type":"application/pdf","locations":{"position":$pos1Based,"progression":$progression},"__v":$VERSION}"""
    }

    /**
     * Decode a locator JSON string back to a 0-based page index, or null if no `position` field.
     *
     * Decision tree:
     * 1. Has `"__v":2` → new shared format, 1-based → return `position - 1`.
     * 2. href matches `/page-N` (old iOS) → 0-based, return `position` directly.
     * 3. Otherwise (Android / Readium `Locator.toJSON()`) → 1-based, return `position - 1`.
     */
    fun decode0Based(locatorJson: String): Int? {
        val position = extractPosition(locatorJson) ?: return null
        return when {
            locatorJson.contains(VERSION_KEY) -> (position - 1).coerceAtLeast(0)
            extractHrefPage(locatorJson) != null -> position
            else -> (position - 1).coerceAtLeast(0)
        }
    }

    private fun extractPosition(json: String): Int? =
        Regex(""""position"\s*:\s*(\d+)""").find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun extractHrefPage(json: String): Int? =
        Regex(""""href"\s*:\s*"/page-(\d+)"""").find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

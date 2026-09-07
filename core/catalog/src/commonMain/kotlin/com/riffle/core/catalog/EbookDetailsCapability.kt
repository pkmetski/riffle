package com.riffle.core.catalog

import com.riffle.core.models.TocEntry

/**
 * Opt-in mixin for Sources that can supply an ebook's **table of contents and reading-length
 * estimate cheaply from catalog metadata — without downloading or opening the book file**.
 *
 * The default detail-screen path opens the EPUB (a quick local read for most Sources). For a Source
 * whose ebook is expensive to materialize — notably [SourceType.OREILLY][com.riffle.core.models.SourceType.OREILLY],
 * where "opening" means scraping and synthesizing the whole book — that would download the entire
 * book just to show an estimate. Such a Source implements this capability so the detail screen can
 * render the TOC + estimate from a couple of cheap metadata calls instead. Sources that don't
 * implement it fall back to the open-the-EPUB extraction unchanged.
 */
interface EbookDetailsCapability : CatalogCapability {
    /** Returns cheap TOC + position count for [itemId], or null when unavailable (caller falls back). */
    suspend fun ebookDetails(itemId: String): CatalogEbookDetails?
}

/**
 * Cheap ebook details for the detail screen. [totalPositions] is in the same unit Readium uses for
 * its reading-time estimate (≈1 KiB positions), so it can feed the existing estimate formula.
 */
data class CatalogEbookDetails(
    val totalPositions: Int?,
    val tocEntries: List<TocEntry>,
    val epubVersion: String? = null,
)

package com.riffle.core.catalog.chitanka

/**
 * Module-internal scraper types. These do NOT cross the module boundary — the
 * [ChitankaCatalog] converts them into `com.riffle.core.catalog.CatalogItem` /
 * `CatalogSeries` / `CatalogFacet` etc. before returning to callers.
 *
 * Kotlin port of `lib/scraper/types.ts` from the reference
 * [chitanka-to-audiobookshelf](https://github.com/pkmetski/chitanka-to-audiobookshelf).
 */

internal enum class ChitankaSite { CHITANKA, GRAMOFONCHE }

internal data class ChitankaBookSummary(
    val url: String,
    val site: ChitankaSite,
    val title: String,
    val authors: List<String>,
    val coverUrl: String?,
    val format: String,          // "epub" or "mp3"
    val duration: String? = null,
)

internal data class ChitankaListingResult(
    val items: List<ChitankaBookSummary>,
    val nextPagePath: String?,
)

internal data class ChitankaCategoryEntry(
    val label: String,
    val path: String,
)

internal data class ChitankaSeriesRef(
    val name: String,
    val sequence: String,
)

internal data class ChitankaDetail(
    val site: ChitankaSite,
    val url: String,
    val title: String,
    val authors: List<String>,
    val translators: List<String>,
    val description: String,
    val genres: List<String>,
    val language: String,
    val year: String,
    val series: ChitankaSeriesRef?,
    val coverUrl: String?,
    val downloadUrl: String?,       // for chitanka only; gramofonche uses [downloads]
    val format: String,             // "epub" for chitanka; "mp3" for gramofonche
    // Gramofonche-only fields:
    val narrators: List<String> = emptyList(),
    val duration: String = "",
    val downloads: List<ChitankaDownload> = emptyList(),
)

internal data class ChitankaDownload(
    val url: String,
    val title: String,
)

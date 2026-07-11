package com.riffle.core.catalog.gutenberg

/**
 * Module-internal parser types. These do NOT cross the module boundary — [GutenbergCatalog]
 * converts them into `com.riffle.core.catalog.CatalogItem` / `CatalogFacet` before returning
 * to callers.
 *
 * Modelled after the Gutendex JSON schema (https://gutendex.com) but pruned to the fields
 * Riffle actually surfaces.
 */

internal data class GutenbergBookSummary(
    val id: Long,
    val title: String,
    val authors: List<String>,
    val languages: List<String>,
    val subjects: List<String>,
    val bookshelves: List<String>,
    val coverUrl: String?,
    val epubUrl: String?,
    val description: String?,
)

internal data class GutenbergListingResult(
    val items: List<GutenbergBookSummary>,
    val next: String?,
    val count: Int,
)

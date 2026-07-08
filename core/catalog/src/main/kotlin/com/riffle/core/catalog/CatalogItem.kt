package com.riffle.core.catalog

/**
 * A single browsable item exposed by a Catalog. Excludes local-only state (progress, downloaded,
 * cached) — those live in local stores keyed on (sourceId, sourceItemId). The repository layer
 * hydrates a [CatalogItem] into a domain `LibraryItem` by joining local state on top.
 *
 * [id] is Source-local. [rootId] identifies the owning [CatalogRoot].
 */
data class CatalogItem(
    val id: String,
    val rootId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val ebookFormat: BookFormat,
    val hasAudio: Boolean = false,
    val audioDurationSec: Double = 0.0,
    val ebookFileIno: String? = null,
    val description: String? = null,
    val seriesName: String? = null,
    /** Position within [seriesName] when the item belongs to a series (e.g. "1", "2.5"). */
    val seriesSequence: String? = null,
    val publishedYear: String? = null,
    val genres: List<String> = emptyList(),
    val publisher: String? = null,
    val language: String? = null,
    val addedAt: Long? = null,
    val isbn: String? = null,
    val asin: String? = null,
    /**
     * Optional server-reported "how far through" fraction (0..1). Only populated by non-progress
     * paths that return items with server-side progress joined (e.g. some catalog list endpoints).
     * Repositories that need authoritative progress consult [ProgressPeerCapability] instead.
     */
    val readingProgress: Float? = null,
    /** Last time this item was updated on the source (used to bust cover-image CDN caches). */
    val updatedAt: Long? = null,
)

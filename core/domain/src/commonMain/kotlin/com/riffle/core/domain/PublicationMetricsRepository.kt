package com.riffle.core.domain

/**
 * File-derived facts used outside the reader. They are cached by the ebook file identity because
 * computing them requires opening the publication with Readium.
 */
data class PublicationMetrics(
    val ebookFileIno: String,
    val totalPositions: Int? = null,
    val pageCount: Int? = null,
    val epubVersion: String? = null,
)

interface PublicationMetricsRepository {
    suspend fun get(sourceId: String, itemId: String): PublicationMetrics?

    suspend fun save(
        sourceId: String,
        itemId: String,
        metrics: PublicationMetrics,
    )
}

package com.riffle.core.catalog

/**
 * An entry in a [CatalogSeries]: an item id paired with its position within the series (e.g. "1",
 * "2.5"). Kept flat rather than embedding a full [CatalogItem] because the refresher pairs entries
 * against the already-materialised item rows.
 */
data class CatalogSeriesEntry(
    val itemId: String,
    val sequence: String?,
)

data class CatalogSeries(
    val id: String,
    val rootId: String,
    val name: String,
    val coverUrl: String?,
    val bookCount: Int,
    /** Ordered entries in the series (server-order); empty when the caller asked for summary only. */
    val items: List<CatalogSeriesEntry> = emptyList(),
)

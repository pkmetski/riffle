package com.riffle.core.catalog

data class CatalogCollection(
    val id: String,
    val rootId: String,
    val name: String,
    val bookCount: Int,
    /** Ordered item ids in the collection; empty when the caller asked for summary only. */
    val itemIds: List<String> = emptyList(),
)

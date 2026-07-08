package com.riffle.core.catalog

data class CatalogSeries(
    val id: String,
    val rootId: String,
    val name: String,
    val coverUrl: String?,
    val bookCount: Int,
)

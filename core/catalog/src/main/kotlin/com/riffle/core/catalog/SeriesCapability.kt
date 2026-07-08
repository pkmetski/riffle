package com.riffle.core.catalog

interface SeriesCapability : CatalogCapability {
    suspend fun listSeries(rootId: String): List<CatalogSeries>
    suspend fun listItemsInSeries(rootId: String, seriesId: String): List<CatalogItem>
}

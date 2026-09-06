package com.riffle.feature.library

import com.riffle.core.catalog.CatalogItem

interface WebSourceLibraryItemUpserter {
    suspend fun upsert(sourceId: String, item: CatalogItem)
}

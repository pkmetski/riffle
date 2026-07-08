package com.riffle.core.catalog

interface CollectionsCapability : CatalogCapability {
    suspend fun listCollections(rootId: String): List<CatalogCollection>
    suspend fun createCollection(rootId: String, name: String): CatalogCollection
    suspend fun addItemToCollection(collectionId: String, itemId: String)
    suspend fun removeItemFromCollection(collectionId: String, itemId: String)
}

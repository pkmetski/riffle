package com.riffle.core.catalog

/**
 * Optional extension for Sources whose server can combine free-text search with the same
 * server-side facets returned by [Catalog.listFacets].
 */
interface FacetedSearchCapability : CatalogCapability {
    suspend fun search(
        rootId: String,
        query: String,
        page: Int = 0,
        pageSize: Int = 50,
        facet: FacetSelection? = null,
    ): List<CatalogItem>
}

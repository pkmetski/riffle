package com.riffle.core.catalog

/**
 * A browsable root within a Source. For ABS this maps to a Library. For flat-catalog Sources
 * (LocalFiles, Gutenberg) [listRoots][Catalog.listRoots] returns a single synthetic root.
 * IDs are Source-local; the (sourceId, rootId) tuple materialises at the repository boundary.
 */
data class CatalogRoot(
    val id: String,
    val name: String,
    val mediaType: String,
    val isUnsupported: Boolean = false,
)

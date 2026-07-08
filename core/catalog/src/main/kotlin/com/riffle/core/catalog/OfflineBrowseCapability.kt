package com.riffle.core.catalog

/**
 * Marker for Catalogs that can serve [Catalog.listRoots], [Catalog.browse], and [Catalog.getItem]
 * from a local cache while [Catalog.connectivityCheck] reports offline. LocalFiles is trivially
 * always-offline-safe; ABS opts in by maintaining a `catalog_cache` (ADR 0041). Sources without
 * this mixin must be treated as network-only.
 */
interface OfflineBrowseCapability : CatalogCapability

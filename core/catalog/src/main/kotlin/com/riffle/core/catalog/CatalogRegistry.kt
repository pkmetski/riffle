package com.riffle.core.catalog

import com.riffle.core.models.Source

/**
 * Resolves the [Catalog] to consult for a given [Source]. Backed by a `Map<SourceType, CatalogFactory>`
 * registered at Hilt time (see `CatalogModule`) — every SourceType is served by exactly one
 * factory, and each factory builds one [Catalog] per Source row.
 *
 * Repositories call [forActive] for the browsing-scoped default (the Source the user is currently
 * viewing) or [forSource] / [forSourceId] to target a specific Source (e.g. an open reader that
 * outlives a Source switch, an item pinned to a particular Source). Returns `null` when the target
 * Source no longer exists, is missing credentials, or has no factory registered for its type.
 */
interface CatalogRegistry {
    /** Catalog for the active Source, or `null` when there is none or credentials are missing. */
    suspend fun forActive(): Catalog?

    /** Catalog for a specific Source row. */
    suspend fun forSource(source: Source): Catalog?

    /** Catalog for a Source resolved by id, or `null` when the row no longer exists. */
    suspend fun forSourceId(sourceId: String): Catalog?
}

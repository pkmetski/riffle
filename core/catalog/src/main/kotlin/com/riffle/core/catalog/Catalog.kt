package com.riffle.core.catalog

import com.riffle.core.domain.SourceType

/**
 * A per-Source browsable origin. Every Source owns exactly one [Catalog] instance; Source identity
 * ([SourceType], the (sourceId) namespace) is implicit to the instance. IDs returned by a Catalog
 * are Source-local — repository code pairs them with `sourceId` when persisting.
 *
 * Opt-in behaviours (series, collections, playlists, progress-peer sync, reading sessions, stats,
 * audiobook media, offline browse) live on [CatalogCapability] mixins. Callers gate on mixin
 * presence via [has].
 */
interface Catalog {
    /** Which Source type this Catalog serves. Mirrors the Source row's `type` column. */
    val sourceType: SourceType

    /**
     * Browsable roots. For ABS these are Libraries; flat-catalog Sources (LocalFiles, Gutenberg)
     * return a single synthetic root. May be empty when the backend genuinely has no roots (a
     * fresh ABS instance with zero libraries) — callers must handle the empty case rather than
     * assume a default is present.
     */
    suspend fun listRoots(): List<CatalogRoot>

    /** Items in [rootId]. Paged; [page] is 0-indexed. */
    suspend fun browse(
        rootId: String,
        sort: SortKey = SortKey.TITLE,
        page: Int = 0,
        pageSize: Int = 50,
    ): List<CatalogItem>

    /** Free-text search within [rootId]. Paged like [browse]. */
    suspend fun search(
        rootId: String,
        query: String,
        page: Int = 0,
        pageSize: Int = 50,
    ): List<CatalogItem>

    /** Single-item lookup; returns `null` when the item no longer exists on the Source. */
    suspend fun getItem(itemId: String): CatalogItem?

    /** Openable handle for [itemId] in [format]. Throws on unsupported [format]. */
    suspend fun fetchFile(itemId: String, format: BookFormat): CatalogFileHandle

    /** Reachability + server-side identifiers; safe to call unauthenticated. */
    suspend fun connectivityCheck(): CatalogHealth
}

/** True when this [Catalog] implements capability [T]. */
inline fun <reified T : CatalogCapability> Catalog.has(): Boolean = this is T

/** Cast to capability [T] or `null` if unsupported. */
inline fun <reified T : CatalogCapability> Catalog.asCap(): T? = this as? T

package com.riffle.core.catalog

import com.riffle.core.models.Source
import com.riffle.core.models.SourceType

/**
 * Builds a [Catalog] for a specific [Source] row. Because per-Source configuration (base URL, auth
 * token, insecure-connection flag) is bound at construction time (see e.g. `AbsCatalogConfig`), a
 * single Hilt map entry (`Map<SourceType, CatalogFactory>`) cannot itself be `Map<SourceType, Catalog>`
 * — one factory per SourceType is registered, and [CatalogRegistry] materialises the per-Source
 * instance when the active Source is known.
 */
interface CatalogFactory {
    /** Which Source type this factory serves. Mirrors [Catalog.sourceType]. */
    val sourceType: SourceType

    /**
     * Build a Catalog for [source]. Returns `null` when the Source cannot yet be spoken to (e.g. an
     * ABS Source with no stored token because login hasn't completed) — a first-class state that
     * repositories translate into an empty result.
     */
    suspend fun create(source: Source): Catalog?
}

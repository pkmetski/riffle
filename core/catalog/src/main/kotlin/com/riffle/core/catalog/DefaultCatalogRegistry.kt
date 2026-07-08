package com.riffle.core.catalog

import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.SourceType

/**
 * Default [CatalogRegistry] implementation: looks the active/target [Source] up in
 * [SourceRepository] and delegates to the [CatalogFactory] registered for the Source's
 * [Source.type]. Catalogs are cheap and stateless; we build a fresh one per call rather than cache,
 * so credential changes (token rotation on re-login, insecure-connection toggle) always take effect
 * on the next resolution without any cache-invalidation bookkeeping.
 */
class DefaultCatalogRegistry(
    private val factories: Map<SourceType, CatalogFactory>,
    private val sourceRepository: SourceRepository,
) : CatalogRegistry {

    override suspend fun forActive(): Catalog? =
        sourceRepository.getActive()?.let { forSource(it) }

    override suspend fun forSourceId(sourceId: String): Catalog? =
        sourceRepository.getById(sourceId)?.let { forSource(it) }

    override suspend fun forSource(source: Source): Catalog? {
        val factory = factories[source.type] ?: return null
        return factory.create(source)
    }
}

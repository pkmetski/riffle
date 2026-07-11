package com.riffle.core.catalog.gutenberg

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceType
import okhttp3.OkHttpClient

/**
 * Builds a [GutenbergCatalog] per Source. Because the Gutenberg Source is zero-config (Gutendex
 * origin hardcoded, anonymous read), the factory needs no per-source auth; every `create` call
 * yields an equivalent instance. Wired into `CatalogRegistry` via Hilt in `core:data`'s
 * `CatalogModule`.
 */
class GutenbergCatalogFactory(
    private val okHttpClient: OkHttpClient,
    private val userAgent: String,
) : CatalogFactory {

    override val sourceType: SourceType = SourceType.GUTENBERG

    override suspend fun create(source: Source): Catalog {
        val http = GutenbergHttpClient(client = okHttpClient, userAgent = userAgent)
        return GutenbergCatalog(http = http, bytesClient = okHttpClient, userAgent = userAgent)
    }
}

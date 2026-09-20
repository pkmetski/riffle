package com.riffle.core.catalog.chitanka

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import io.ktor.client.HttpClient

/**
 * Builds a [ChitankaCatalog] per Source. Because the Chitanka Source is zero-config
 * (both domains hardcoded, anonymous read), the factory needs no per-source auth; every
 * `create` call yields an equivalent instance. Wired into `CatalogRegistry` via Hilt in
 * `core:data`'s `CatalogModule`.
 */
class ChitankaCatalogFactory(
    private val httpClient: HttpClient,
    private val userAgent: String,
) : CatalogFactory {

    override val sourceType: SourceType = SourceType.CHITANKA

    override suspend fun create(source: Source): Catalog {
        val http = ChitankaHttpClient(client = httpClient, userAgent = userAgent)
        return ChitankaCatalog(http = http, bytesClient = httpClient, userAgent = userAgent)
    }
}

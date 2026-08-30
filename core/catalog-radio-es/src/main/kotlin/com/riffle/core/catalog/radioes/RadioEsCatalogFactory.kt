package com.riffle.core.catalog.radioes

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import io.ktor.client.HttpClient

class RadioEsCatalogFactory(
    private val httpClient: HttpClient,
    private val userAgent: String,
    private val acceptLanguage: String = "en",
) : CatalogFactory {

    override val sourceType: SourceType = SourceType.RADIO_ES

    override suspend fun create(source: Source): Catalog {
        val http = RadioEsHttpClient(
            client = httpClient,
            userAgent = userAgent,
            acceptLanguage = acceptLanguage,
        )
        return RadioEsCatalog(http = http)
    }
}

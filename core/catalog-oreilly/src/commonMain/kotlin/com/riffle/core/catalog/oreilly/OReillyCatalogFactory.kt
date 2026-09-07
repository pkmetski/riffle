package com.riffle.core.catalog.oreilly

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.domain.BookPreparationProgress
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import io.ktor.client.HttpClient

/**
 * Builds an [OReillyCatalog] per O'Reilly Source row.
 *
 * The authenticated session is the **live** cookie jar from the WebView the user logged in through
 * — supplied by [cookieProvider] — NOT a stored snapshot. O'Reilly's `orm-jwt` rotates and the
 * content endpoints require the *complete, correctly-formatted* cookie string
 * (`orm-jwt=…; groot_sessionid=…; …`); a stale or partial cookie makes O'Reilly serve a truncated
 * DRM sample instead of the full chapter. Reading the cookie live (as the web reader does) keeps the
 * session current and correctly formatted. [cookieProvider] returns null/blank when the user isn't
 * logged in, which the repository reads as "not yet usable" rather than an error.
 *
 * [cookieProvider] is the thin platform seam (Android: WebView `CookieManager`; iOS later:
 * `WKHTTPCookieStore`), keeping the rest of the source shared.
 */
class OReillyCatalogFactory(
    private val httpClient: HttpClient,
    private val cookieProvider: () -> String?,
    private val bookPreparationProgress: BookPreparationProgress? = null,
    private val baseUrl: String = OReillyApi.DEFAULT_BASE_URL,
) : CatalogFactory {

    override val sourceType: SourceType = SourceType.OREILLY

    override suspend fun create(source: Source): Catalog? {
        val cookieHeader = cookieProvider()?.takeIf { it.isNotBlank() } ?: return null
        val api = OReillyApi(client = httpClient, cookieHeader = cookieHeader, baseUrl = source.url.value.ifBlank { baseUrl })
        return OReillyCatalog(
            api = api,
            bytesClient = httpClient,
            cookieHeader = cookieHeader,
            onProgress = { done, total -> bookPreparationProgress?.report(done, total) },
            onProgressDone = { bookPreparationProgress?.clear() },
        )
    }
}

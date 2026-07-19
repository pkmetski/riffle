package com.riffle.core.catalog.komga

import com.riffle.core.catalog.Catalog
import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.domain.TokenStorage
import okhttp3.OkHttpClient

/**
 * Builds a [KomgaCatalog] per Komga Source row. Reconstructs the HTTP Basic auth header from
 * (username, password) — password is fetched from [TokenStorage] and never leaves the process.
 * Returns null when the source has no stored password (fresh row that hasn't finished login).
 */
class KomgaCatalogFactory(
    private val okHttpClient: OkHttpClient,
    private val tokenStorage: TokenStorage,
    private val userAgent: String = "Riffle/dev (Android) komga-source",
) : CatalogFactory {

    override val sourceType: SourceType = SourceType.KOMGA

    override suspend fun create(source: Source): Catalog? {
        // Prefer the pre-built `Basic <base64>` header stashed in the token slot by
        // `KomgaCredentialedAuthenticator` — it's a single-lookup path and works even before
        // the encrypted password store finishes propagating on first install (the previous
        // getPassword-based path returned null for a beat right after Add-Source, which the
        // drawer read as "no Komga catalog → hide the Downloads link" until app restart).
        // Fall back to rebuilding the header from password for rows that predate the token-slot
        // convention.
        val basicAuthHeader = tokenStorage.getToken(source.id)?.takeIf { it.startsWith("Basic ") }
            ?: tokenStorage.getPassword(source.id)?.let { buildBasicAuthHeader(source.username, it) }
            ?: return null
        val config = KomgaCatalogConfig(
            baseUrl = source.url.value,
            basicAuthHeader = basicAuthHeader,
            insecureAllowed = source.insecureConnectionAllowed,
        )
        val http = KomgaHttpClient(
            client = okHttpClient,
            basicAuthHeader = basicAuthHeader,
            userAgent = userAgent,
        )
        return KomgaCatalog(config = config, http = http, bytesClient = okHttpClient)
    }
}

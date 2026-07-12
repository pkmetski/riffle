package com.riffle.core.catalog.komga

/**
 * Per-instance configuration for a [KomgaCatalog]. Auth is HTTP Basic — [basicAuthHeader] is the
 * `Authorization` header value ("Basic base64(user:password)") baked in at construction.
 */
data class KomgaCatalogConfig(
    val baseUrl: String,
    val basicAuthHeader: String,
    val insecureAllowed: Boolean,
)

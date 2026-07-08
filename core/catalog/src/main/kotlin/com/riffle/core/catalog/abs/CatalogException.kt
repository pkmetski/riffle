package com.riffle.core.catalog.abs

import com.riffle.core.network.NetworkResult

/**
 * Thrown by [AbsCatalog] on non-success network results. Variants mirror [NetworkResult] so the
 * repository layer (issue #434) can pattern-match on cause without inspecting the raw network
 * layer. Consumers that only care whether the call succeeded can just let this propagate.
 */
sealed class CatalogException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class Offline(cause: Throwable) : CatalogException("Offline", cause)
    class Auth : CatalogException("Unauthorized")
    class ServerError(val code: Int, msg: String?) : CatalogException("HTTP $code${msg?.let { ": $it" } ?: ""}")
    class Parse(cause: Throwable) : CatalogException("Parse error", cause)
    class Insecure(val cause0: Throwable? = null) : CatalogException("Insecure connection", cause0)
    class UnsupportedFormat(msg: String) : CatalogException(msg)
    class Unknown(cause: Throwable) : CatalogException("Unknown", cause)
}

internal fun <T> NetworkResult<T>.unwrap(): T = when (this) {
    is NetworkResult.Success -> value
    is NetworkResult.Offline -> throw CatalogException.Offline(cause)
    NetworkResult.Auth -> throw CatalogException.Auth()
    is NetworkResult.ServerError -> throw CatalogException.ServerError(code, errorMessage)
    is NetworkResult.Parse -> throw CatalogException.Parse(cause)
    is NetworkResult.InsecureConnection -> throw CatalogException.Insecure()
    is NetworkResult.Unknown -> throw CatalogException.Unknown(cause)
}

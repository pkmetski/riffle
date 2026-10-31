package com.riffle.core.catalog.oreilly

fun parseOrmJwtFromCookieHeader(header: String?): String? {
    if (header.isNullOrBlank()) return null
    for (part in header.split(';')) {
        val trimmed = part.trim()
        if (trimmed.startsWith("orm-jwt=")) {
            val value = trimmed.removePrefix("orm-jwt=").trim()
            if (value.isNotBlank()) return value
        }
    }
    return null
}

/**
 * Extracts the user UUID from the `sub` claim of the `orm-jwt` JWT embedded in [cookieHeader].
 *
 * The JWT payload (second dot-separated segment) is base64url-encoded JSON. The `sub` field is the
 * O'Reilly user UUID needed for per-user API calls like the reading-history endpoint. No network
 * call is required — the value is already present in the cookie Riffle harvests at login.
 *
 * Returns null when the cookie is absent, the JWT is malformed, or the `sub` claim is missing.
 */
internal fun uuidFromCookieHeader(cookieHeader: String): String? {
    val jwt = parseOrmJwtFromCookieHeader(cookieHeader) ?: return null
    val payloadSegment = jwt.split('.').getOrNull(1) ?: return null
    val paddedPayload = payloadSegment
        .replace('-', '+')
        .replace('_', '/')
        .let { s -> s + "=".repeat((4 - s.length % 4) % 4) }
    val payloadJson = runCatching {
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        kotlin.io.encoding.Base64.Default.decode(paddedPayload).decodeToString()
    }.getOrNull() ?: return null
    return Regex(""""sub"\s*:\s*"([^"]+)"""").find(payloadJson)?.groupValues?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
}

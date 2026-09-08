package com.riffle.feature.source.ui

/**
 * Host component of a WebDAV base URL, for the status card's `user@host · url` line.
 *
 * Replaces `java.net.URI(baseUrl).host` (JVM-only). Matches the previous behaviour including its
 * fallback: `URI.host` is null for a scheme-less string, and the old call site fell back to the
 * whole `baseUrl` in that case — so this returns [baseUrl] unchanged when no authority is present.
 */
internal fun webdavHostOf(baseUrl: String): String {
    val schemeEnd = baseUrl.indexOf("://")
    if (schemeEnd <= 0) return baseUrl
    val authority = baseUrl.substring(schemeEnd + 3)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    // Strip any userinfo, then the port. IPv6 literals keep their brackets.
    val hostPort = authority.substringAfterLast('@')
    val host = if (hostPort.startsWith("[")) {
        hostPort.substringBefore(']') + "]"
    } else {
        hostPort.substringBefore(':')
    }
    return host.ifEmpty { baseUrl }
}

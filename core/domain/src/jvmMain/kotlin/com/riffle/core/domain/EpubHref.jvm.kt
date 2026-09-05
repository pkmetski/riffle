package com.riffle.core.domain

/**
 * JVM/Android path extraction — byte-identical to the original `normalizeEpubHref` implementation
 * that lived in `jvmMain`: `java.net.URI(raw).path` (decoded path, `null` for opaque/relative-only
 * URIs), falling back to `null` on any parse failure so the caller can return the raw string.
 */
internal actual fun uriPath(raw: String): String? =
    try {
        java.net.URI(raw).path
    } catch (_: Exception) {
        null
    }

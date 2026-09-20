package com.riffle.core.catalog.chitanka

/**
 * Multiplatform stand-in for `java.net.URI(base).resolve(ref).toASCIIString()`, which the
 * scrapers used while they lived in `jvmMain`. `java.net` has no `commonMain` equivalent, so
 * the RFC 3986 reference-resolution steps this module actually exercises are spelled out here:
 *
 *  - a reference starting with `/` replaces the base path wholesale;
 *  - anything else is merged onto the base path up to its last `/`;
 *  - `.` / `..` segments are removed (RFC 3986 §5.2.4);
 *  - the result is percent-encoded to pure ASCII, exactly like `URI.toASCIIString()`.
 *
 * Scheme-relative (`//host/…`) and absolute (`https://…`) references never reach here —
 * [ChitankaScraper.toAbsolute] short-circuits both before calling.
 *
 * Throws [IllegalArgumentException] for the same inputs `java.net.URI`'s single-argument
 * constructor rejects (illegal ASCII characters, malformed `%` escapes, a base with no
 * `scheme://`), so [ChitankaScraper.toAbsolute]'s `catch` still falls back to `BASE + href`
 * for exactly the inputs it used to.
 */
internal fun resolveAgainstBase(baseUrl: String, reference: String): String {
    requireParseable(baseUrl)
    requireParseable(reference)

    val schemeEnd = baseUrl.indexOf("://")
    require(schemeEnd > 0) { "base URL is not absolute: $baseUrl" }
    var authorityEnd = schemeEnd + "://".length
    while (authorityEnd < baseUrl.length &&
        baseUrl[authorityEnd] != '/' &&
        baseUrl[authorityEnd] != '?' &&
        baseUrl[authorityEnd] != '#'
    ) {
        authorityEnd++
    }
    val origin = baseUrl.substring(0, authorityEnd)
    val basePath = baseUrl.substring(authorityEnd).substringBefore('#').substringBefore('?')

    // Split the reference into path / query / fragment so dot-segment removal only touches
    // the path — `..` inside a query string is data, not a path segment.
    val fragmentStart = reference.indexOf('#')
    val fragment = if (fragmentStart >= 0) reference.substring(fragmentStart) else ""
    val withoutFragment = if (fragmentStart >= 0) reference.substring(0, fragmentStart) else reference
    val queryStart = withoutFragment.indexOf('?')
    val query = if (queryStart >= 0) withoutFragment.substring(queryStart) else ""
    val referencePath = if (queryStart >= 0) withoutFragment.substring(0, queryStart) else withoutFragment

    // Only a merged path gets dot-segment removal. An absolute-path reference is taken
    // verbatim — `java.net.URI.resolve` normalises `resolvePath` output but hands a
    // `/`-rooted reference straight through, so `/a/./b/../c` survives intact there too.
    val path = when {
        // Fragment-only reference ("#note-3"): the base path is kept whole.
        referencePath.isEmpty() && query.isEmpty() && fragment.isNotEmpty() -> basePath
        referencePath.startsWith("/") -> referencePath
        // A query-only reference (`?page=2`) falls through here with an empty path and, like
        // `java.net.URI`, resolves against the base directory rather than the base document.
        else -> removeDotSegments(basePath.substringBeforeLast('/', "") + "/" + referencePath)
    }
    return percentEncodeNonAscii(origin + path + query + fragment)
}

/**
 * RFC 3986 §5.2.4 `remove_dot_segments`, preserving a leading and trailing `/`.
 *
 * A `..` that would climb above the root is kept rather than dropped, because that is what
 * `java.net.URI.resolve` does (`https://host/` + `../a` → `https://host/../a`) and the whole
 * point of this helper is to be indistinguishable from it.
 */
private fun removeDotSegments(path: String): String {
    if (path.isEmpty()) return path
    val segments = ArrayDeque<String>()
    // True when the segment just processed collapsed away — a path ending in a collapsed
    // segment keeps its trailing "/", but a ".." that survives verbatim does not gain one.
    var lastSegmentCollapsed = false
    for (segment in path.split('/')) {
        when (segment) {
            // Empty segments collapse here, matching java.net.URI.normalize (`a//b` → `a/b`).
            "" -> lastSegmentCollapsed = false
            "." -> lastSegmentCollapsed = true
            ".." -> if (segments.isNotEmpty() && segments.last() != "..") {
                segments.removeLast()
                lastSegmentCollapsed = true
            } else {
                segments.addLast("..")
                lastSegmentCollapsed = false
            }
            else -> {
                segments.addLast(segment)
                lastSegmentCollapsed = false
            }
        }
    }
    val body = segments.joinToString("/")
    val leading = if (path.startsWith("/")) "/" else ""
    val trailing = if (body.isNotEmpty() && (path.endsWith("/") || lastSegmentCollapsed)) "/" else ""
    return leading + body + trailing
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * UTF-8 percent-encodes every non-ASCII code unit, matching `java.net.URI.toASCIIString()`.
 * Already-encoded `%XX` triples are left untouched (they are ASCII).
 */
private fun percentEncodeNonAscii(url: String): String {
    if (url.all { it.code < 0x80 }) return url
    val bytes = url.encodeToByteArray()
    return buildString(bytes.size) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value < 0x80) {
                append(value.toChar())
            } else {
                append('%').append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0F])
            }
        }
    }
}

/** ASCII characters `java.net.URI` refuses to parse unless they arrive percent-encoded. */
private const val ILLEGAL_URI_ASCII = " <>\"{}|\\^`"

/**
 * Mirrors the validation `java.net.URI`'s single-argument constructor performs, so callers that
 * relied on it throwing keep seeing an exception for the same strings.
 */
private fun requireParseable(value: String) {
    var i = 0
    while (i < value.length) {
        val c = value[i]
        require(c.code > 0x20 && c.code != 0x7F && !(c.code in 0x80..0x9F)) {
            "illegal character U+${c.code.toString(16)} in URI: $value"
        }
        require(c !in ILLEGAL_URI_ASCII) { "illegal character '$c' in URI: $value" }
        if (c == '%') {
            require(i + 2 < value.length && value[i + 1].isUriHex() && value[i + 2].isUriHex()) {
                "malformed percent-escape in URI: $value"
            }
            i += 2
        }
        i++
    }
}

private fun Char.isUriHex(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

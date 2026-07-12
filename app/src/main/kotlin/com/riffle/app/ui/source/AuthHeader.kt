package com.riffle.app.ui.source

/**
 * Convert a stored source token into a full `Authorization` header value. Existing sources
 * (Audiobookshelf, Storyteller) store an opaque bearer token — those callers previously hard-coded
 * `"Bearer $token"` at every cover-fetch site. Komga's HTTP-Basic auth ships the FULL header value
 * ("Basic <base64>") through the same token slot; if the string already carries a scheme prefix we
 * return it verbatim so the fetch site does not double up ("Bearer Basic …"), which the server
 * rejects.
 *
 * Empty string maps to empty string (rather than "Bearer ") so callers can distinguish "no token"
 * from a real header — Coil's [ImageRequest.addHeader] rejects blank names but accepts blank
 * values, and passing a bare "Bearer " to some servers is worse than sending no header at all.
 */
fun String.asAuthHeader(): String = when {
    isEmpty() -> ""
    startsWith("Basic ") || startsWith("Bearer ") || startsWith("Digest ") -> this
    else -> "Bearer $this"
}

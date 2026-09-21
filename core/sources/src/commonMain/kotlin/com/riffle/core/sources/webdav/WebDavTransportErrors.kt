package com.riffle.core.sources.webdav

import kotlinx.io.IOException

/**
 * Is [throwable] a TLS failure rather than a plain connection failure?
 *
 * The distinction matters to the user: a network error means "try again", a TLS error means "your
 * certificate is wrong" and the connection will never succeed unaided. It is the one piece of the
 * WebDAV client that genuinely cannot be shared — the JVM has `javax.net.ssl.SSLException`, and
 * Darwin surfaces the same condition as an `NSError` inside a Ktor engine exception.
 *
 * Everything else routes through [IOException], which Ktor types as `java.io.IOException` on the
 * JVM and `kotlinx.io.IOException` on Native, so the network branch needs no `expect` at all.
 */
expect fun isWebDavTlsFailure(throwable: Throwable): Boolean

/**
 * Whether a transport failure's message describes a TLS/certificate problem.
 *
 * Used by the Darwin actual, where the only thing carried across the engine boundary is a
 * message. Shared and `internal` rather than private to that actual so the keyword list is
 * assertable from `commonTest` — the classification is invisible until a user with a bad
 * certificate reports "it just says network error".
 */
internal fun looksLikeTlsFailure(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val lower = message.lowercase()
    return TLS_MARKERS.any { it in lower }
}

private val TLS_MARKERS = listOf(
    "ssl",
    "tls",
    "certificate",
    "cert ",
    "handshake",
    "trust",
    // NSURLErrorSecureConnectionFailed and the NSURLErrorServerCertificate* family. Darwin
    // reports these as bare codes in the error description when there is no friendlier text.
    "-1200",
    "-1201",
    "-1202",
    "-1203",
    "-1204",
    "-1205",
    "-1206",
)

/**
 * Runs [block], converting transport failures into the [AnnotationSyncException] the sync layer
 * understands. Anything that is not a transport failure — including
 * [AnnotationSyncException.AuthFailed] thrown by the block itself — propagates untouched.
 */
internal suspend inline fun <T> classifyWebDavTransportErrors(crossinline block: suspend () -> T): T {
    return try {
        block()
    } catch (e: Throwable) {
        when {
            isWebDavTlsFailure(e) -> throw AnnotationSyncException.TlsError(e.message ?: "TLS error", e)
            e is IOException -> throw AnnotationSyncException.NetworkError(e.message ?: "network error", e)
            else -> throw e
        }
    }
}

/**
 * Maps a transport failure onto the connection-test result. Returns null when [throwable] is not
 * a transport failure at all, so the caller can rethrow.
 */
internal fun webDavTransportTestResult(throwable: Throwable): TestConnectionResult? = when {
    isWebDavTlsFailure(throwable) -> TestConnectionResult.TlsError(throwable.message ?: "TLS error")
    throwable is IOException -> TestConnectionResult.NetworkError(throwable.message ?: "Network error")
    else -> null
}

package com.riffle.core.sources.webdav

/**
 * Darwin has no `SSLException`: Ktor's Darwin engine wraps the underlying `NSError` and the only
 * thing that crosses the boundary is its description, so the TLS branch is decided by
 * [looksLikeTlsFailure] over the message (and over the whole cause chain, because the engine
 * exception's own message is sometimes just "Failed to execute request").
 *
 * Getting this wrong is not fatal — the request still fails — but the user is told "network
 * error, try again" for a certificate that will never be accepted, which is the difference
 * between a fixable problem and an unexplained one.
 */
actual fun isWebDavTlsFailure(throwable: Throwable): Boolean {
    var current: Throwable? = throwable
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (looksLikeTlsFailure(current.message)) return true
        if (looksLikeTlsFailure(current::class.simpleName)) return true
        current = current.cause
        depth++
    }
    return false
}

/** Guards against a self-referential cause chain, which Ktor engine exceptions can produce. */
private const val MAX_CAUSE_DEPTH = 8

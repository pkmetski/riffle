package com.riffle.core.sources.komga

internal actual fun isSslHandshakeError(e: Throwable): Boolean {
    val msg = e.message ?: return false
    return msg.contains("SSL", ignoreCase = true) ||
        msg.contains("certificate", ignoreCase = true) ||
        msg.contains("handshake", ignoreCase = true)
}

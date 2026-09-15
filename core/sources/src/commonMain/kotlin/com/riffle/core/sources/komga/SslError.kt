package com.riffle.core.sources.komga

/** Returns true when [e] represents a TLS/SSL handshake failure (e.g. self-signed certificate). */
internal expect fun isSslHandshakeError(e: Throwable): Boolean

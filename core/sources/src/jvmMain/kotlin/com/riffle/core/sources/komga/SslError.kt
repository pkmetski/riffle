package com.riffle.core.sources.komga

import javax.net.ssl.SSLHandshakeException

internal actual fun isSslHandshakeError(e: Throwable): Boolean = e is SSLHandshakeException

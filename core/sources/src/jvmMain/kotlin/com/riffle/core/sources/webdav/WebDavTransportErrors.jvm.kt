package com.riffle.core.sources.webdav

import javax.net.ssl.SSLException

/**
 * The JVM classification, unchanged from before the WebDAV client moved to `commonMain`:
 * `SSLException` is the TLS branch, every other `IOException` is the network branch.
 */
actual fun isWebDavTlsFailure(throwable: Throwable): Boolean = throwable is SSLException

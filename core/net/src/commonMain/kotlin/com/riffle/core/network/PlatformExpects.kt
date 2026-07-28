package com.riffle.core.network

import io.ktor.client.HttpClient

// ---------------------------------------------------------------------------
// Exception-classifier helpers — JVM throws SSLHandshakeException/IOException;
// iOS throws NSError/NSURLError. Platform actuals do the type check.
// ---------------------------------------------------------------------------

internal expect fun isSSLHandshakeException(e: Throwable): Boolean
internal expect fun isIOException(e: Throwable): Boolean

/** Creates a platform-appropriate "insecure connection" throwable for errorAsThrowable(). */
internal expect fun newSSLHandshakeException(msg: String): Throwable

/** Creates a platform-appropriate IO throwable for errorAsThrowable(). */
internal expect fun newIOException(msg: String): Throwable

// ---------------------------------------------------------------------------
// TLS bypass — androidMain wraps OkHttpClient; iosMain is a no-op (Darwin
// already reads the system trust store, and InsecureTls is Android-only UX).
// ---------------------------------------------------------------------------

/** Returns a copy of this client that skips TLS certificate validation. */
expect fun HttpClient.withInsecureTls(): HttpClient

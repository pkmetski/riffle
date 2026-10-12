package com.riffle.core.network

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorDomain
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression: isSSLHandshakeException() on iOS always returned false before the fix, so the app
 * treated self-signed certificate failures as generic IO errors rather than showing the dedicated
 * "Allow insecure connection" UI (#1208).
 */
class IosSSLExceptionDetectionTest {

    @Test
    fun nsUrlErrorSecureConnectionFailedIsDetected() {
        val nsError = NSError(domain = NSURLErrorDomain, code = -1200L, userInfo = null)
        val exception = DarwinHttpRequestException(nsError)
        assertTrue(isSSLHandshakeException(exception))
    }

    @Test
    fun nsUrlErrorServerCertificateUntrustedIsDetected() {
        val nsError = NSError(domain = NSURLErrorDomain, code = -1202L, userInfo = null)
        val exception = DarwinHttpRequestException(nsError)
        assertTrue(isSSLHandshakeException(exception))
    }

    @Test
    fun nsUrlErrorServerCertificateHasUnknownRootIsDetected() {
        val nsError = NSError(domain = NSURLErrorDomain, code = -1203L, userInfo = null)
        val exception = DarwinHttpRequestException(nsError)
        assertTrue(isSSLHandshakeException(exception))
    }

    @Test
    fun genericNetworkErrorIsNotDetectedAsSSL() {
        // NSURLErrorNotConnectedToInternet = -1009
        val nsError = NSError(domain = NSURLErrorDomain, code = -1009L, userInfo = null)
        val exception = DarwinHttpRequestException(nsError)
        assertFalse(isSSLHandshakeException(exception))
    }

    @Test
    fun nonDarwinExceptionIsNotDetectedAsSSL() {
        assertFalse(isSSLHandshakeException(RuntimeException("some other error")))
    }
}

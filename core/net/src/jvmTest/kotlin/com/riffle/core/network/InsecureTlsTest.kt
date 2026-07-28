package com.riffle.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLException

private fun Throwable.hasSslCause(): Boolean {
    val seen = mutableSetOf<Throwable>()
    val stack = ArrayDeque<Throwable>().apply { add(this@hasSslCause) }
    while (stack.isNotEmpty()) {
        val e = stack.removeFirst()
        if (!seen.add(e)) continue
        if (e is SSLException) return true
        e.cause?.let { stack.add(it) }
        e.suppressed.forEach { stack.add(it) }
    }
    return false
}

class InsecureTlsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        // Self-signed server cert bound to `localhost` — a plain client rejects it, a
        // `withInsecureTls()` client accepts it.
        val heldCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        server = MockWebServer().apply {
            useHttps(serverCerts.sslSocketFactory(), false)
            enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            enqueue(MockResponse().setResponseCode(200).setBody("ok"))
            start()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `default client rejects self-signed cert`() {
        val client = OkHttpClient()
        try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
            fail("expected SSL failure against self-signed cert")
        } catch (e: Exception) {
            // OkHttp's fast-fallback wraps the SSL failure as a ConnectException with the real
            // handshake failure as a suppressed cause; either surface is acceptable proof that
            // the plain client refuses the self-signed chain.
            assertTrue(
                "expected SSL cause, got ${e.stackTraceToString()}",
                e.hasSslCause(),
            )
        }
    }

    @Test
    fun `withInsecureTls client accepts self-signed cert`() {
        val client = OkHttpClient().withInsecureTls()
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("ok", response.body?.string())
        }
    }

    @Test
    fun `withInsecureTls returns a new client without mutating the source`() {
        val base = OkHttpClient()
        val insecure = base.withInsecureTls()
        assertNotSame(base, insecure)
        assertNotSame(base.sslSocketFactory, insecure.sslSocketFactory)
        // Base client still rejects — proves the original is untouched.
        try {
            base.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
            fail("base client should still reject self-signed cert")
        } catch (e: Exception) {
            assertTrue("expected SSL cause, got ${e.stackTraceToString()}", e.hasSslCause())
        }
        // Trust manager returns no accepted issuers — Riffle's homelab-friendly semantics.
        assertTrue(insecure.x509TrustManager?.acceptedIssuers?.isEmpty() == true)
    }
}

package com.riffle.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class KomgaServerInfoApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: KomgaServerInfoApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = KomgaServerInfoApiClient(createDefaultHttpClient(OkHttpClient()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getServerVersion parses build version from actuator info`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"build":{"version":"1.19.0","artifact":"komga","name":"Komga"}}""")
                .addHeader("Content-Type", "application/json"),
        )
        val version = client.getServerVersion(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "test",
            password = "secret",
            insecureAllowed = false,
        )
        assertEquals("1.19.0", version)
    }

    @Test
    fun `getServerVersion sends Basic auth header`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"build":{"version":"1.0.0"}}""")
                .addHeader("Content-Type", "application/json"),
        )
        client.getServerVersion(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "admin",
            password = "pass",
            insecureAllowed = false,
        )
        val request = server.takeRequest()
        assertEquals("/actuator/info", request.path)
        assertEquals(
            KomgaServerInfoApiClient.buildBasicAuthHeader("admin", "pass"),
            request.getHeader("Authorization"),
        )
    }

    @Test
    fun `getServerVersion returns null on 403 forbidden`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val version = client.getServerVersion(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "user",
            password = "wrong",
            insecureAllowed = false,
        )
        assertNull(version)
    }

    @Test
    fun `getServerVersion returns null when build version is absent`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"git":{"branch":"main"}}""")
                .addHeader("Content-Type", "application/json"),
        )
        val version = client.getServerVersion(
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "u",
            password = "p",
            insecureAllowed = false,
        )
        assertNull(version)
    }

    @Test
    fun `parseActuatorVersion extracts version string`() {
        val body = """{"build":{"version":"1.12.3","artifact":"komga"}}"""
        assertEquals("1.12.3", KomgaServerInfoApiClient.parseActuatorVersion(body))
    }

    @Test
    fun `parseActuatorVersion returns null for missing build object`() {
        assertNull(KomgaServerInfoApiClient.parseActuatorVersion("""{}"""))
    }

    @Test
    fun `parseActuatorVersion returns null for non-JSON`() {
        assertNull(KomgaServerInfoApiClient.parseActuatorVersion("not-json"))
    }
}

package com.riffle.core.network

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KomgaServerInfoApiClientTest {

    @Test
    fun `getServerVersion parses build version from actuator info`() = runTest {
        val (_, client) = mockKomgaServerInfoClient(
            """{"build":{"version":"1.19.0","artifact":"komga","name":"Komga"}}""" to HttpStatusCode.OK,
        )
        val version = client.getServerVersion(
            baseUrl = BASE_URL,
            username = "test",
            password = "secret",
            insecureAllowed = false,
        )
        assertEquals(version, "1.19.0")
    }

    @Test
    fun `getServerVersion sends Basic auth header`() = runTest {
        val (engine, client) = mockKomgaServerInfoClient(
            """{"build":{"version":"1.0.0"}}""" to HttpStatusCode.OK,
        )
        client.getServerVersion(
            baseUrl = BASE_URL,
            username = "admin",
            password = "pass",
            insecureAllowed = false,
        )
        val req = engine.requestHistory[0]
        assertEquals(req.url.encodedPath, "/actuator/info")
        assertEquals(
            KomgaServerInfoApiClient.buildBasicAuthHeader("admin", "pass"),
            req.headers["Authorization"],
        )
    }

    @Test
    fun `getServerVersion returns null on 403 forbidden`() = runTest {
        val (_, client) = mockKomgaServerInfoClient("" to HttpStatusCode.Forbidden)
        val version = client.getServerVersion(
            baseUrl = BASE_URL,
            username = "user",
            password = "wrong",
            insecureAllowed = false,
        )
        assertNull(version)
    }

    @Test
    fun `getServerVersion returns null when build version is absent`() = runTest {
        val (_, client) = mockKomgaServerInfoClient(
            """{"git":{"branch":"main"}}""" to HttpStatusCode.OK,
        )
        val version = client.getServerVersion(
            baseUrl = BASE_URL,
            username = "u",
            password = "p",
            insecureAllowed = false,
        )
        assertNull(version)
    }

    @Test
    fun `parseActuatorVersion extracts version string`() {
        val body = """{"build":{"version":"1.12.3","artifact":"komga"}}"""
        assertEquals(KomgaServerInfoApiClient.parseActuatorVersion(body), "1.12.3")
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

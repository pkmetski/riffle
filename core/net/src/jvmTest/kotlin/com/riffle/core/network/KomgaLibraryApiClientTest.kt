package com.riffle.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KomgaLibraryApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: KomgaLibraryApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = KomgaLibraryApiClient(createDefaultHttpClient(OkHttpClient()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun `getLibraries parses id and name from response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""[{"id":"lib1","name":"Comics","unavailable":false},{"id":"lib2","name":"Manga","unavailable":false}]""")
                .addHeader("Content-Type", "application/json"),
        )
        val result = client.getLibraries(baseUrl(), token = "Basic dGVzdA==", insecureAllowed = false)
        assertTrue(result is NetworkResult.Success)
        val libs = (result as NetworkResult.Success).value
        assertEquals(2, libs.size)
        assertEquals(KomgaLibraryInfo(id = "lib1", name = "Comics"), libs[0])
        assertEquals(KomgaLibraryInfo(id = "lib2", name = "Manga"), libs[1])
    }

    @Test
    fun `getLibraries sends Authorization header verbatim`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("[]")
                .addHeader("Content-Type", "application/json"),
        )
        client.getLibraries(baseUrl(), token = "Basic YWRtaW46cGFzcw==", insecureAllowed = false)
        val request = server.takeRequest()
        assertEquals("/api/v1/libraries", request.path)
        assertEquals("Basic YWRtaW46cGFzcw==", request.getHeader("Authorization"))
    }

    @Test
    fun `getLibraries returns empty list when server returns empty array`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("[]")
                .addHeader("Content-Type", "application/json"),
        )
        val result = client.getLibraries(baseUrl(), token = "Basic dGVzdA==", insecureAllowed = false)
        assertTrue(result is NetworkResult.Success)
        assertTrue((result as NetworkResult.Success).value.isEmpty())
    }

    @Test
    fun `getLibraries returns Auth on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client.getLibraries(baseUrl(), token = "Basic bad", insecureAllowed = false)
        assertTrue(result is NetworkResult.Auth)
    }

    @Test
    fun `getLibraries returns ServerError on 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = client.getLibraries(baseUrl(), token = "Basic bad", insecureAllowed = false)
        assertTrue(result is NetworkResult.ServerError)
        assertEquals(403, (result as NetworkResult.ServerError).code)
    }
}

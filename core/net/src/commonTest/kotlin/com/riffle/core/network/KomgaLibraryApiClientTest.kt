package com.riffle.core.network

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KomgaLibraryApiClientTest {

    @Test
    fun `getLibraries parses id and name from response`() = runTest {
        val (_, client) = mockKomgaLibraryClient(
            """[{"id":"lib1","name":"Comics","unavailable":false},{"id":"lib2","name":"Manga","unavailable":false}]""" to HttpStatusCode.OK,
        )
        val result = client.getLibraries(BASE_URL, token = "Basic dGVzdA==", insecureAllowed = false)
        assertTrue(result is NetworkResult.Success)
        val libs = (result as NetworkResult.Success).value
        assertEquals(2, libs.size)
        assertEquals(KomgaLibraryInfo(id = "lib1", name = "Comics"), libs[0])
        assertEquals(KomgaLibraryInfo(id = "lib2", name = "Manga"), libs[1])
    }

    @Test
    fun `getLibraries sends Authorization header verbatim`() = runTest {
        val (engine, client) = mockKomgaLibraryClient("[]" to HttpStatusCode.OK)
        client.getLibraries(BASE_URL, token = "Basic YWRtaW46cGFzcw==", insecureAllowed = false)
        val req = engine.requestHistory[0]
        assertEquals(req.url.encodedPath, "/api/v1/libraries")
        assertEquals(req.headers["Authorization"], "Basic YWRtaW46cGFzcw==")
    }

    @Test
    fun `getLibraries returns empty list when server returns empty array`() = runTest {
        val (_, client) = mockKomgaLibraryClient("[]" to HttpStatusCode.OK)
        val result = client.getLibraries(BASE_URL, token = "Basic dGVzdA==", insecureAllowed = false)
        assertTrue(result is NetworkResult.Success)
        assertTrue((result as NetworkResult.Success).value.isEmpty())
    }

    @Test
    fun `getLibraries returns Auth on 401`() = runTest {
        val (_, client) = mockKomgaLibraryClient("" to HttpStatusCode.Unauthorized)
        val result = client.getLibraries(BASE_URL, token = "Basic bad", insecureAllowed = false)
        assertTrue(result is NetworkResult.Auth)
    }

    @Test
    fun `getLibraries returns ServerError on 403`() = runTest {
        val (_, client) = mockKomgaLibraryClient("" to HttpStatusCode.Forbidden)
        val result = client.getLibraries(BASE_URL, token = "Basic bad", insecureAllowed = false)
        assertTrue(result is NetworkResult.ServerError)
        assertEquals(403, (result as NetworkResult.ServerError).code)
    }
}

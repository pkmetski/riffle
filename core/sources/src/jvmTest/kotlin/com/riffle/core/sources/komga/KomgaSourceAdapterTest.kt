package com.riffle.core.sources.komga

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.models.InsecureConnectionType
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class KomgaSourceAdapterTest {

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun mockClient(vararg responses: Pair<String, HttpStatusCode>): Pair<MockEngine, HttpClient> {
        val queue = ArrayDeque(responses.toList())
        val engine = MockEngine { _ ->
            val (body, status) = queue.removeFirst()
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = jsonHeaders(),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return engine to client
    }

    private fun url(scheme: String = "https") = SourceUrl.parse("$scheme://komga.example.com")!!

    @Test fun `success returns PendingSource with SourceType KOMGA and libraries`() = runTest {
        val (engine, client) = mockClient(
            """{"id":"u1","email":"u@x.com"}""" to HttpStatusCode.OK,
            """[{"id":"L1","name":"Comics"},{"id":"L2","name":"Manga"}]""" to HttpStatusCode.OK,
        )

        val result = KomgaSourceAdapter(client).authenticate(
            url = url(),
            username = "alice",
            password = "secret",
            insecureAllowed = true,
            serverType = ServerType.AUDIOBOOKSHELF,
        )

        assertTrue(result is AuthenticateResult.Success)
        val pending = (result as AuthenticateResult.Success).pending
        assertEquals(SourceType.KOMGA, pending.sourceType)
        assertEquals("alice", pending.username)
        assertEquals("secret", pending.password)
        assertEquals("u1", pending.userId)
        assertEquals(2, pending.libraries.size)
        assertEquals("L1", pending.libraries[0].id)
        assertEquals("Comics", pending.libraries[0].name)
        // First request must go to v2/users/me with Basic auth.
        val meReq = engine.requestHistory[0]
        assertEquals("/api/v2/users/me", meReq.url.encodedPath)
        assertTrue(meReq.headers[HttpHeaders.Authorization]!!.startsWith("Basic "))
    }

    @Test fun `401 on users me maps to WrongCredentials`() = runTest {
        val (_, client) = mockClient("""{}""" to HttpStatusCode.Unauthorized)

        val result = KomgaSourceAdapter(client).authenticate(
            url = url(),
            username = "alice",
            password = "wrong",
            insecureAllowed = true,
            serverType = ServerType.AUDIOBOOKSHELF,
        )

        assertTrue(result is AuthenticateResult.WrongCredentials)
    }

    @Test fun `403 on users me maps to WrongCredentials`() = runTest {
        val (_, client) = mockClient("""{}""" to HttpStatusCode.Forbidden)

        val result = KomgaSourceAdapter(client).authenticate(
            url = url(),
            username = "alice",
            password = "wrong",
            insecureAllowed = true,
            serverType = ServerType.AUDIOBOOKSHELF,
        )

        assertTrue(result is AuthenticateResult.WrongCredentials)
    }

    @Test fun `http URL without insecureAllowed flags InsecureConnection HTTP`() = runTest {
        val (_, client) = mockClient()

        val result = KomgaSourceAdapter(client).authenticate(
            url = url("http"),
            username = "alice",
            password = "p",
            insecureAllowed = false,
            serverType = ServerType.AUDIOBOOKSHELF,
        )

        assertTrue(result is AuthenticateResult.InsecureConnection)
        assertEquals(InsecureConnectionType.HTTP, (result as AuthenticateResult.InsecureConnection).type)
    }

    @Test fun `v2 404 falls back to v1 users me then succeeds`() = runTest {
        val (engine, client) = mockClient(
            """{}""" to HttpStatusCode.NotFound,             // v2 /api/v2/users/me
            """{"id":"u2"}""" to HttpStatusCode.OK,         // v1 /api/v1/users/me
            """[]""" to HttpStatusCode.OK,                   // /api/v1/libraries
        )

        val result = KomgaSourceAdapter(client).authenticate(
            url = url(),
            username = "alice",
            password = "p",
            insecureAllowed = true,
            serverType = ServerType.AUDIOBOOKSHELF,
        )

        assertTrue(result is AuthenticateResult.Success)
        assertEquals(0, (result as AuthenticateResult.Success).pending.libraries.size)
        assertEquals("/api/v2/users/me", engine.requestHistory[0].url.encodedPath)
        assertEquals("/api/v1/users/me", engine.requestHistory[1].url.encodedPath)
        assertEquals("/api/v1/libraries", engine.requestHistory[2].url.encodedPath)
    }

    @Test fun `Basic auth header is encoded correctly`() = runTest {
        val (engine, client) = mockClient(
            """{"id":"u1"}""" to HttpStatusCode.OK,
            """[]""" to HttpStatusCode.OK,
        )

        KomgaSourceAdapter(client).authenticate(url(), "alice", "s3cr3t", insecureAllowed = true, serverType = ServerType.AUDIOBOOKSHELF)

        val authHeader = engine.requestHistory[0].headers[HttpHeaders.Authorization]!!
        assertTrue(authHeader.startsWith("Basic "))
        val decoded = String(Base64.getDecoder().decode(authHeader.removePrefix("Basic ")))
        assertEquals("alice:s3cr3t", decoded)
    }

    @Test fun `http URL with insecureAllowed proceeds normally`() = runTest {
        val (_, client) = mockClient(
            """{"id":"u1"}""" to HttpStatusCode.OK,
            """[]""" to HttpStatusCode.OK,
        )

        val result = KomgaSourceAdapter(client).authenticate(
            url = url("http"),
            username = "alice",
            password = "p",
            insecureAllowed = true,
            serverType = ServerType.AUDIOBOOKSHELF,
        )

        assertTrue(result is AuthenticateResult.Success)
    }
}

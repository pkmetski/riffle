package com.riffle.core.data.credentialed

import com.riffle.core.domain.AuthenticateResult
import com.riffle.core.models.InsecureConnectionType
import com.riffle.core.models.ServerType
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KomgaCredentialedAuthenticatorTest {

    private lateinit var server: MockWebServer
    private lateinit var auth: KomgaCredentialedAuthenticator

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        auth = KomgaCredentialedAuthenticator(OkHttpClient())
    }

    @After fun tearDown() { server.shutdown() }

    private fun url(): SourceUrl = SourceUrl.parse(server.url("/").toString().trimEnd('/'))!!

    @Test fun `success returns PendingSource with SourceType KOMGA and libraries`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"u1","email":"u@x"}"""))
        server.enqueue(MockResponse().setBody("""[{"id":"L1","name":"Comics"},{"id":"L2","name":"Manga"}]"""))

        val result = auth.authenticate(
            url = url(),
            username = "u",
            password = "p",
            insecureAllowed = true,
            serverType = ServerType.AUDIOBOOKSHELF,
        )

        assertTrue(result is AuthenticateResult.Success)
        val pending = (result as AuthenticateResult.Success).pending
        assertEquals(SourceType.KOMGA, pending.sourceType)
        assertEquals("u", pending.username)
        assertEquals("p", pending.password)
        assertEquals(2, pending.libraries.size)
        assertEquals("L1", pending.libraries[0].id)
        assertEquals("Comics", pending.libraries[0].name)
        // First request goes to /api/v2/users/me and carries Basic auth.
        val meReq = server.takeRequest()
        assertEquals("/api/v2/users/me", meReq.path)
        assertTrue(meReq.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test fun `401 on users me maps to WrongCredentials`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = auth.authenticate(url(), "u", "wrong", insecureAllowed = true, serverType = ServerType.AUDIOBOOKSHELF)

        assertTrue(result is AuthenticateResult.WrongCredentials)
    }

    @Test fun `http URL without insecureAllowed flags InsecureConnection`() = runTest {
        val result = auth.authenticate(
            url = SourceUrl.parse("http://media-server:25600")!!,
            username = "u",
            password = "p",
            insecureAllowed = false,
            serverType = ServerType.AUDIOBOOKSHELF,
        )

        assertTrue(result is AuthenticateResult.InsecureConnection)
        assertEquals(
            InsecureConnectionType.HTTP,
            (result as AuthenticateResult.InsecureConnection).type,
        )
    }

    @Test fun `v2 404 falls back to v1 users me`() = runTest {
        // v2 → 404, v1 → 200, libraries → success.
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"id":"u1"}"""))
        server.enqueue(MockResponse().setBody("""[]"""))

        val result = auth.authenticate(url(), "u", "p", insecureAllowed = true, serverType = ServerType.AUDIOBOOKSHELF)

        assertTrue(result is AuthenticateResult.Success)
        assertEquals("/api/v2/users/me", server.takeRequest().path)
        assertEquals("/api/v1/users/me", server.takeRequest().path)
        assertEquals("/api/v1/libraries", server.takeRequest().path)
    }
}

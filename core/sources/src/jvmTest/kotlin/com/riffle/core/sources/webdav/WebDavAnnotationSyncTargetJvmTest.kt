package com.riffle.core.sources.webdav

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import javax.net.ssl.SSLException

class WebDavAnnotationSyncTargetJvmTest {

    private fun makeTargetWithFailingClient(throwable: Throwable): WebDavAnnotationSyncTarget {
        val failEngine = MockEngine { throw throwable }
        return WebDavAnnotationSyncTarget(
            baseUrl = Url("https://example.test/dav/"),
            username = "u",
            password = "p",
            client = HttpClient(failEngine),
            dispatchers = com.riffle.core.domain.DefaultDispatcherProvider,
        )
    }

    @Test fun `list wraps SSLException as TlsError`() = runTest {
        val target = makeTargetWithFailingClient(SSLException("cert untrusted"))

        try {
            target.list("namespace-1", "item-1")
            kotlin.test.fail("expected TlsError")
        } catch (e: AnnotationSyncException.TlsError) {
            assertTrue(e.message!!.contains("cert untrusted"))
        }
    }
}

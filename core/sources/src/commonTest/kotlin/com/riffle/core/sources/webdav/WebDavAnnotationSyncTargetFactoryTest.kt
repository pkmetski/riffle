package com.riffle.core.sources.webdav

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.DispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.Test

private val testDispatchers = object : DispatcherProvider {
    override val main = Dispatchers.Unconfined
    override val mainImmediate = Dispatchers.Unconfined
    override val io = Dispatchers.Unconfined
    override val default = Dispatchers.Default
}

class WebDavAnnotationSyncTargetFactoryTest {

    private val factory = WebDavAnnotationSyncTargetFactory(
        httpClient = HttpClient(MockEngine { respond(ByteReadChannel(""), HttpStatusCode.OK) }),
        dispatchers = testDispatchers,
    )

    @Test fun `valid config produces a target`() {
        val target = factory.create(
            AnnotationSyncConfig(
                baseUrl = "https://dav.example.org/remote.php/dav/files/me/annotations",
                username = "u",
                password = "p",
            ),
        )
        assertNotNull(target)
    }

    @Test fun `malformed base URL yields null`() {
        val target = factory.create(
            AnnotationSyncConfig(baseUrl = "::not a url::", username = "u", password = "p"),
        )
        assertNull(target)
    }

    @Test fun `empty base URL yields null`() {
        val target = factory.create(
            AnnotationSyncConfig(baseUrl = "", username = "u", password = "p"),
        )
        assertNull(target)
    }
}

package com.riffle.core.sources.webdav

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.DefaultDispatcherProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavProgressEnumeratorTest {

    private val config = AnnotationSyncConfig(
        baseUrl = "https://webdav.example.com/riffle/",
        username = "user",
        password = "pass",
    )
    private val namespace = "chitanka_user"

    private fun makeEnumerator(engine: MockEngine) = WebDavProgressEnumerator(
        httpClient = HttpClient(engine),
        dispatchers = DefaultDispatcherProvider,
    )

    private fun propfindResponse(filenames: List<String>): String {
        val responses = filenames.joinToString("") { filename ->
            """<D:response><D:href>/riffle/$filename</D:href><D:propstat><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"""
        }
        return """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">$responses</D:multistatus>"""
    }

    private fun propfindEngine(filenames: List<String>) = MockEngine {
        respond(
            content = ByteReadChannel(propfindResponse(filenames)),
            status = HttpStatusCode.MultiStatus,
            headers = headersOf("Content-Type", "application/xml"),
        )
    }

    @Test fun `enumerate returns ebook safeId from ebook progress file`() = runTest {
        val engine = propfindEngine(listOf("chitanka_user__book.12073-foo__progress.json"))
        val result = makeEnumerator(engine).enumerate(config, namespace)
        assertEquals(listOf("book.12073-foo"), result.ebookSafeIds)
        assertTrue(result.audioSafeIds.isEmpty())
    }

    @Test fun `enumerate returns audio safeId from audio progress file`() = runTest {
        val engine = propfindEngine(listOf("chitanka_user__book.42__audio_progress.json"))
        val result = makeEnumerator(engine).enumerate(config, namespace)
        assertEquals(listOf("book.42"), result.audioSafeIds)
        assertTrue(result.ebookSafeIds.isEmpty())
    }

    @Test fun `enumerate separates ebook and audio files in same response`() = runTest {
        val engine = propfindEngine(listOf(
            "chitanka_user__book.12073__progress.json",
            "chitanka_user__book.99__audio_progress.json",
        ))
        val result = makeEnumerator(engine).enumerate(config, namespace)
        assertEquals(listOf("book.12073"), result.ebookSafeIds)
        assertEquals(listOf("book.99"), result.audioSafeIds)
    }

    @Test fun `enumerate ignores files from other namespaces`() = runTest {
        val engine = propfindEngine(listOf(
            "chitanka_user__book.1__progress.json",
            "gutenberg_user__book.2__progress.json",
            "other__book.3__progress.json",
        ))
        val result = makeEnumerator(engine).enumerate(config, namespace)
        assertEquals(listOf("book.1"), result.ebookSafeIds)
    }

    @Test fun `enumerate returns empty on 404`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.NotFound) }
        val result = makeEnumerator(engine).enumerate(config, namespace)
        assertTrue(result.ebookSafeIds.isEmpty())
        assertTrue(result.audioSafeIds.isEmpty())
    }

    @Test fun `enumerate returns empty on network error`() = runTest {
        val engine = MockEngine { throw java.io.IOException("network error") }
        val result = makeEnumerator(engine).enumerate(config, namespace)
        assertTrue(result.ebookSafeIds.isEmpty())
        assertTrue(result.audioSafeIds.isEmpty())
    }

    @Test fun `enumerate returns empty on malformed base URL`() = runTest {
        val badConfig = config.copy(baseUrl = "not a url")
        val engine = MockEngine { respond("", HttpStatusCode.OK) }
        val result = makeEnumerator(engine).enumerate(badConfig, namespace)
        assertTrue(result.ebookSafeIds.isEmpty())
    }

    @Test fun `enumerate ignores annotation files and device-meta files`() = runTest {
        val engine = propfindEngine(listOf(
            "chitanka_user__book.1__book.1__annotations-deviceA.jsonld",
            "chitanka_user__device-meta-deviceA.json",
            "chitanka_user__book.2__progress.json",
        ))
        val result = makeEnumerator(engine).enumerate(config, namespace)
        assertEquals(listOf("book.2"), result.ebookSafeIds)
    }

    @Test fun `enumerate handles namespace with slash replaced by dot`() = runTest {
        val engine = propfindEngine(listOf("chitanka.alice__book.5__progress.json"))
        val result = makeEnumerator(engine).enumerate(config, "chitanka/alice")
        assertEquals(listOf("book.5"), result.ebookSafeIds)
    }

    // ── progressFileUrl suffix ───────────────────────────────────────────────

    @Test fun `progressFileUrl with ebook suffix matches EBOOK_PROGRESS_SUFFIX`() {
        val url = WebDavProgressRemote.progressFileUrl("https://dav.test/", "ns", "item")
        assertTrue(url.endsWith(WebDavProgressRemote.EBOOK_PROGRESS_SUFFIX))
    }

    @Test fun `progressFileUrl with audio suffix produces distinct filename from ebook`() {
        val ebook = WebDavProgressRemote.progressFileUrl("https://dav.test/", "ns", "item", WebDavProgressRemote.EBOOK_PROGRESS_SUFFIX)
        val audio = WebDavProgressRemote.progressFileUrl("https://dav.test/", "ns", "item", WebDavProgressRemote.AUDIO_PROGRESS_SUFFIX)
        assertTrue(audio.endsWith(WebDavProgressRemote.AUDIO_PROGRESS_SUFFIX))
        assertTrue(audio != ebook)
    }

    @Test fun `audio suffix does not end with ebook suffix so enumerator filter is unambiguous`() {
        assertFalse(
            "audio suffix must not end with ebook suffix or enumerator would double-classify",
            WebDavProgressRemote.AUDIO_PROGRESS_SUFFIX.endsWith(WebDavProgressRemote.EBOOK_PROGRESS_SUFFIX),
        )
    }
}

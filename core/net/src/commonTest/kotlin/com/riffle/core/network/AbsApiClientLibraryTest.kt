package com.riffle.core.network

import com.riffle.core.models.EbookFormat
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbsApiClientLibraryTest {

    @Test
    fun `getLibraries success parses all libraries`() = runTest {
        val (_, client) = mockAbsClient(
            """{"libraries":[{"id":"lib-1","name":"My Books","mediaType":"book"},{"id":"lib-2","name":"Podcasts","mediaType":"podcast"}]}""" to HttpStatusCode.OK,
        )
        val result = client.getLibraries(BASE_URL, "token-abc", false)
        assertTrue(result is NetworkResult.Success)
        val success = result as NetworkResult.Success
        assertEquals(2, success.value.size)
        assertEquals(success.value[0].id, "lib-1")
        assertEquals(success.value[0].name, "My Books")
        assertEquals(success.value[0].mediaType, "book")
    }

    @Test
    fun `getLibraries parses audiobooksOnly from settings`() = runTest {
        val (_, client) = mockAbsClient(
            """{"libraries":[{"id":"lib-1","name":"Books","mediaType":"book","settings":{"audiobooksOnly":false}},{"id":"lib-2","name":"Audiobooks","mediaType":"book","settings":{"audiobooksOnly":true}}]}""" to HttpStatusCode.OK,
        )
        val result = client.getLibraries(BASE_URL, "token", false)
        val success = result as NetworkResult.Success
        assertEquals(false, success.value[0].audiobooksOnly)
        assertEquals(true, success.value[1].audiobooksOnly)
    }

    @Test
    fun `getLibraries preserves upload folder ids`() = runTest {
        val (_, client) = mockAbsClient(
            """{"libraries":[{"id":"lib-1","name":"Books","mediaType":"book","folders":[{"id":"folder-1","fullPath":"/books"}]}]}""" to HttpStatusCode.OK,
        )
        val result = client.getLibraries(BASE_URL, "token", false)
        val library = (result as NetworkResult.Success).value.single()
        assertEquals(library.folders.single().id, "folder-1")
        assertEquals(library.folders.single().fullPath, "/books")
    }

    @Test
    fun `getLibraries sends Authorization Bearer header and calls correct path`() = runTest {
        val (engine, client) = mockAbsClient("""{"libraries":[]}""" to HttpStatusCode.OK)
        client.getLibraries(BASE_URL, "my-token", false)
        val req = engine.requestHistory[0]
        assertEquals(req.headers["Authorization"], "Bearer my-token")
        assertEquals(req.url.encodedPath, "/api/libraries")
    }

    @Test
    fun `getLibraries returns NetworkError on unreachable host`() = runTest {
        val client = offlineAbsClient()
        val result = client.getLibraries("http://127.0.0.1:1", "token", false)
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `getLibraryItems marks ebook items as supported and audiobook items as unsupported`() = runTest {
        val (_, client) = mockAbsClient(
            ("""{"results":[""" +
                """{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"My Ebook","authorName":"Author A"},"ebookFormat":"epub"},"userMediaProgress":{"progress":0.5,"ebookProgress":0.42}},""" +
                """{"id":"item-2","libraryId":"lib-1","media":{"metadata":{"title":"My Audiobook","authorName":"Author B"}}}""" +
                """]}""") to HttpStatusCode.OK,
        )
        val result = client.getLibraryItems(BASE_URL, "lib-1", "token-abc", false)
        assertTrue(result is NetworkResult.Success)
        val success = result as NetworkResult.Success
        assertEquals(2, success.value.size)
        assertEquals(EbookFormat.Epub, success.value[0].ebookFormat)
        assertEquals(0.42f, success.value[0].readingProgress!!, 0.001f)
        assertEquals(EbookFormat.Unsupported, success.value[1].ebookFormat)
    }

    @Test
    fun `getLibraryItems sets hasAudio from numAudioFiles and numTracks false when absent`() = runTest {
        val (_, client) = mockAbsClient(
            ("""{"results":[""" +
                """{"id":"ebook","libraryId":"lib-1","media":{"metadata":{"title":"Ebook","authorName":"A"},"ebookFormat":"epub"}},""" +
                """{"id":"audio","libraryId":"lib-1","media":{"metadata":{"title":"Audiobook","authorName":"B"},"numAudioFiles":6,"duration":39214.5}},""" +
                """{"id":"audio-tracks","libraryId":"lib-1","media":{"metadata":{"title":"Audiobook2","authorName":"C"},"numTracks":3}},""" +
                """{"id":"combined","libraryId":"lib-1","media":{"metadata":{"title":"Both","authorName":"D"},"ebookFormat":"epub","numAudioFiles":2}}""" +
                """]}""") to HttpStatusCode.OK,
        )
        val result = client.getLibraryItems(BASE_URL, "lib-1", "token-abc", false)
        val items = (result as NetworkResult.Success).value.associateBy { it.id }
        assertFalse(items.getValue("ebook").hasAudio)
        assertTrue(items.getValue("audio").hasAudio)
        assertTrue(items.getValue("audio-tracks").hasAudio)
        assertTrue(items.getValue("combined").hasAudio)
        assertEquals(39214.5, items.getValue("audio").audioDurationSec, 0.001)
        assertEquals(0.0, items.getValue("ebook").audioDurationSec, 0.001)
    }

    @Test
    fun `getLibraryItems uses 0 progress when userMediaProgress is null`() = runTest {
        val (_, client) = mockAbsClient(
            """{"results":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"Unread Book","authorName":"Author B"},"ebookFormat":"pdf"}}]}""" to HttpStatusCode.OK,
        )
        val result = client.getLibraryItems(BASE_URL, "lib-1", "token-abc", false)
        val success = result as NetworkResult.Success
        assertNull(success.value[0].readingProgress)
    }

    @Test
    fun `getLibraryItems sends correct path and auth header`() = runTest {
        val (engine, client) = mockAbsClient("""{"results":[]}""" to HttpStatusCode.OK)
        client.getLibraryItems(BASE_URL, "lib-99", "tok-xyz", false)
        val req = engine.requestHistory[0]
        assertEquals(req.headers["Authorization"], "Bearer tok-xyz")
        assertEquals(req.url.encodedPath, "/api/libraries/lib-99/items")
    }

    @Test
    fun `getLibraryItems returns NetworkError on unreachable host`() = runTest {
        val client = offlineAbsClient()
        val result = client.getLibraryItems("http://127.0.0.1:1", "lib-1", "token", false)
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `getLibraryItems returns NetworkError on 404 with plain-text body`() = runTest {
        // ABS returns plain text "Library not found" with HTTP 404 when a stale/removed library
        // id is queried. Parsing that body as JSON used to crash the app on the library screen.
        val (_, client) = mockAbsClient("Library not found" to HttpStatusCode.NotFound)
        val result = client.getLibraryItems(BASE_URL, "lib-gone", "tok", false)
        assertTrue(result !is NetworkResult.Success)
    }

    @Test
    fun `getLibraryItems parses addedAt timestamp`() = runTest {
        val (_, client) = mockAbsClient(
            """{"results":[{"id":"item-1","libraryId":"lib-1","addedAt":1708369906982,"media":{"metadata":{"title":"Dune","authorName":"Frank Herbert"},"ebookFormat":"epub"}}]}""" to HttpStatusCode.OK,
        )
        val result = client.getLibraryItems(BASE_URL, "lib-1", "token", false)
        val success = result as NetworkResult.Success
        assertEquals(1708369906982L, success.value[0].addedAt)
    }

    @Test
    fun `getRecentlyAddedLibraryItems requests newest items first`() = runTest {
        val (engine, client) = mockAbsClient("""{"results":[]}""" to HttpStatusCode.OK)
        client.getRecentlyAddedLibraryItems(
            BASE_URL,
            "lib-1",
            limit = 10,
            token = "tok",
            insecureAllowed = false,
        )
        val req = engine.requestHistory[0]
        assertTrue(req.url.encodedPathAndQuery.startsWith("/api/libraries/lib-1/items?limit=10&sort=random&_riffle_refresh="))
        assertEquals(req.headers["Cache-Control"], "no-cache, no-store")
        assertEquals(req.headers["Authorization"], "Bearer tok")
    }

    @Test
    fun `scanLibrary posts to the selected library`() = runTest {
        val (engine, client) = mockAbsClient("" to HttpStatusCode.OK)
        val result = client.scanLibrary(BASE_URL, "lib-1", "tok", false)
        assertTrue(result is NetworkResult.Success)
        val req = engine.requestHistory[0]
        assertEquals(req.method.value, "POST")
        assertEquals(req.url.encodedPath, "/api/libraries/lib-1/scan")
        assertTrue(req.url.encodedPathAndQuery.contains("force=1"))
        assertEquals(req.headers["Authorization"], "Bearer tok")
    }

    @Test
    fun `getLibraryItems parses updatedAt timestamp`() = runTest {
        val (_, client) = mockAbsClient(
            """{"results":[{"id":"item-1","libraryId":"lib-1","updatedAt":1719000000000,"media":{"metadata":{"title":"Dune","authorName":"Frank Herbert"},"ebookFormat":"epub"}}]}""" to HttpStatusCode.OK,
        )
        val result = client.getLibraryItems(BASE_URL, "lib-1", "token", false)
        val success = result as NetworkResult.Success
        assertEquals(1719000000000L, success.value[0].updatedAt)
    }

    @Test
    fun `getLibraryItems sets updatedAt to null when field is absent`() = runTest {
        val (_, client) = mockAbsClient(
            """{"results":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"Dune","authorName":"Frank Herbert"},"ebookFormat":"epub"}}]}""" to HttpStatusCode.OK,
        )
        val result = client.getLibraryItems(BASE_URL, "lib-1", "token", false)
        val success = result as NetworkResult.Success
        assertNull(success.value[0].updatedAt)
    }

    @Test
    fun `getLibraryItems sets addedAt to null when field is absent`() = runTest {
        val (_, client) = mockAbsClient(
            """{"results":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"Dune","authorName":"Frank Herbert"},"ebookFormat":"epub"}}]}""" to HttpStatusCode.OK,
        )
        val result = client.getLibraryItems(BASE_URL, "lib-1", "token", false)
        val success = result as NetworkResult.Success
        assertNull(success.value[0].addedAt)
    }

    // --- /api/me mediaProgress.lastUpdate (drives cross-device "In Progress" sort) ---

    @Test
    fun `getUserProgress parses lastUpdate from real-shaped mediaProgress entries`() = runTest {
        // Body modelled on a real ABS /api/me response — extra fields ignored, lastUpdate captured.
        val (_, client) = mockAbsClient(
            """{
                "mediaProgress":[
                    {"id":"p1","userId":"u","libraryItemId":"item-1","mediaItemType":"book",
                     "progress":0.0,"ebookProgress":0.015625,"isFinished":false,
                     "lastUpdate":1780170049396,"startedAt":1779780482497},
                    {"id":"p2","userId":"u","libraryItemId":"item-2","mediaItemType":"book",
                     "progress":0.0,"ebookProgress":0.5,"isFinished":false,
                     "lastUpdate":1779642817411}
                ]}""".trimIndent() to HttpStatusCode.OK,
        )

        val result = client.getUserProgress(BASE_URL, "tok", false)

        assertTrue(result is NetworkResult.Success)
        val byItemId = (result as NetworkResult.Success).value
        assertEquals(2, byItemId.size)
        assertEquals(1_780_170_049_396L, byItemId["item-1"]?.lastUpdate)
        assertEquals(0.015625f, byItemId["item-1"]?.ebookProgress)
        assertEquals(1_779_642_817_411L, byItemId["item-2"]?.lastUpdate)
    }

    @Test
    fun `getUserProgress surfaces audiobook progress even when ebookProgress is zero`() = runTest {
        // An audiobook entry: real listen `progress`, with `ebookProgress` 0 (no ebook). The mapping
        // must not let the 0 ebookProgress shadow the listen position (ADR 0035) — regression for
        // "audiobook progress not visible in the library".
        val (_, client) = mockAbsClient(
            """{
                "mediaProgress":[
                    {"libraryItemId":"audio-1","mediaItemType":"book",
                     "progress":0.42,"ebookProgress":0.0,"isFinished":false,"lastUpdate":1780170049396}
                ]}""".trimIndent() to HttpStatusCode.OK,
        )

        val result = client.getUserProgress(BASE_URL, "tok", false)

        val byItemId = (result as NetworkResult.Success).value
        assertEquals(0.42f, byItemId["audio-1"]?.ebookProgress)
    }

    @Test
    fun `getUserProgress passes audio position through instead of collapsing to the stored scalar`() = runTest {
        // Regression for the "progress bar jumps back and forth" bug: ABS's stored `progress`
        // scalar can be stale (a client once PATCHed it computed against the wrong duration)
        // while `currentTime`/`duration` remain authoritative. When audio position data is
        // present, the mapping must surface it raw and must NOT fold the scalar into
        // ebookProgress — otherwise the bulk library refresh writes the stale scalar over the
        // audio-derived fraction the per-item pull just wrote.
        val (_, client) = mockAbsClient(
            """{
                "mediaProgress":[
                    {"libraryItemId":"audio-1","mediaItemType":"book",
                     "progress":0.05,"ebookProgress":0.0,"currentTime":148.3,"duration":251.4,
                     "isFinished":false,"lastUpdate":1780170049396}
                ]}""".trimIndent() to HttpStatusCode.OK,
        )

        val result = client.getUserProgress(BASE_URL, "tok", false)

        val entry = (result as NetworkResult.Success).value["audio-1"]!!
        assertEquals(148.3, entry.currentTime, 0.0001)
        assertEquals(251.4, entry.duration, 0.0001)
        assertEquals(false, entry.isFinished)
        assertNull(entry.ebookProgress)
    }

    @Test
    fun `getUserProgress parses isFinished from mediaProgress entries`() = runTest {
        val (_, client) = mockAbsClient(
            """{
                "mediaProgress":[
                    {"libraryItemId":"item-1","progress":1.0,"isFinished":true,"lastUpdate":100}
                ]}""".trimIndent() to HttpStatusCode.OK,
        )

        val result = client.getUserProgress(BASE_URL, "tok", false)

        assertEquals(true, (result as NetworkResult.Success).value["item-1"]?.isFinished)
    }

    @Test
    fun `getUserProgress yields null lastUpdate when field is absent`() = runTest {
        val (_, client) = mockAbsClient(
            """{"mediaProgress":[{"libraryItemId":"item-1","ebookProgress":0.25}]}""" to HttpStatusCode.OK,
        )

        val result = client.getUserProgress(BASE_URL, "tok", false)

        val byItemId = (result as NetworkResult.Success).value
        assertNull(byItemId["item-1"]?.lastUpdate)
        assertEquals(0.25f, byItemId["item-1"]?.ebookProgress)
    }
}

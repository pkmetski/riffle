package com.riffle.core.network

import com.riffle.core.models.EbookFormat
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbsApiClientSeriesCollectionTest {

    // ── getSeries ────────────────────────────────────────────────────────────

    @Test
    fun `getSeries parses series list from API response`() = runTest {
        val (_, client) = mockAbsClient(
            ("""{"results":[{"id":"ser-1","name":"My Series","libraryId":"lib-1","books":[""" +
                """{"id":"item-1","libraryId":"lib-1","seriesSequence":"1","media":{"metadata":{"title":"Book One","authorName":"Author A"},"ebookFormat":"epub"}}""" +
                """]}]}""") to HttpStatusCode.OK,
        )
        val result = client.getSeries(BASE_URL, "lib-1", "token", false)
        assertTrue(result is NetworkResult.Success)
        val success = result as NetworkResult.Success
        assertEquals(1, success.value.size)
        assertEquals(success.value[0].id, "ser-1")
        assertEquals(success.value[0].name, "My Series")
        assertEquals(success.value[0].libraryId, "lib-1")
        assertEquals(1, success.value[0].bookCount)
    }

    @Test
    fun `getSeries parses items with sequence and progress`() = runTest {
        val (_, client) = mockAbsClient(
            ("""{"results":[{"id":"ser-1","name":"S","libraryId":"lib-1","books":[""" +
                """{"id":"item-1","libraryId":"lib-1","seriesSequence":"2","media":{"metadata":{"title":"B","authorName":"A"},"ebookFormat":"epub"},"userMediaProgress":{"progress":0.3,"ebookProgress":0.75}},""" +
                """{"id":"item-2","libraryId":"lib-1","seriesSequence":null,"media":{"metadata":{"title":"C","authorName":"A"}}}""" +
                """]}]}""") to HttpStatusCode.OK,
        )
        val result = client.getSeries(BASE_URL, "lib-1", "token", false)
        val items = (result as NetworkResult.Success).value[0].items
        assertEquals(2, items.size)
        assertEquals(items[0].sequence, "2")
        assertEquals(0.75f, items[0].readingProgress!!, 0.001f)
        assertEquals(EbookFormat.Epub, items[0].ebookFormat)
        assertNull(items[1].sequence)
        assertNull(items[1].readingProgress)
        assertEquals(EbookFormat.Unsupported, items[1].ebookFormat)
    }

    @Test
    fun `getSeries sends correct path and auth header`() = runTest {
        val (engine, client) = mockAbsClient("""{"results":[]}""" to HttpStatusCode.OK)
        client.getSeries(BASE_URL, "lib-99", "tok-xyz", false)
        val req = engine.requestHistory[0]
        assertEquals(req.headers["Authorization"], "Bearer tok-xyz")
        assertEquals(req.url.encodedPath, "/api/libraries/lib-99/series")
        assertTrue(req.url.encodedPathAndQuery.contains("limit=500"))
    }

    @Test
    fun `getSeries parses series when libraryId is absent from series object`() = runTest {
        val (_, client) = mockAbsClient(
            """{"results":[{"id":"ser-1","name":"No LibId Series","books":[]}]}""" to HttpStatusCode.OK,
        )
        val result = client.getSeries(BASE_URL, "lib-1", "token", false)
        assertTrue(result is NetworkResult.Success)
        val series = (result as NetworkResult.Success).value
        assertEquals(1, series.size)
        assertEquals(series[0].libraryId, "lib-1")
    }

    @Test
    fun `getSeries parses real server response shape`() = runTest {
        // JSON captured from a real Audiobookshelf server (minified=1, one series, one book)
        val realJson = """{"results":[{"id":"6824c42f-856b-409c-b32e-ad4a472f7447","name":"Discworld","nameIgnorePrefix":"Discworld","description":null,"addedAt":1706605805384,"updatedAt":1706605805384,"libraryId":"e77c113d-4383-488d-956f-89c18db431ac","books":[{"id":"3567e8b4-bed8-442f-9733-5dd642295462","ino":"1511850","oldLibraryItemId":null,"libraryId":"e77c113d-4383-488d-956f-89c18db431ac","folderId":"a4f7da01-fa75-45bf-96e5-693df9b6e6a6","path":"/books/Terry Pratchett/The Colour of Magic","relPath":"Terry Pratchett/The Colour of Magic","isFile":false,"mtimeMs":1708371028869,"ctimeMs":1762849628483,"birthtimeMs":0,"addedAt":1708369906982,"updatedAt":1762902014957,"isMissing":false,"isInvalid":false,"mediaType":"book","media":{"id":"8e4da48f-e0aa-4dd0-813a-9e2c15da892b","metadata":{"title":"The Colour Of Magic","titleIgnorePrefix":"Colour Of Magic, The","subtitle":null,"authorName":"Terry Pratchett","authorNameLF":"Pratchett, Terry","narratorName":"","seriesName":"Discworld #1","genres":["Fiction"],"publishedYear":"1983","publishedDate":null,"publisher":"Random House","description":"description text","isbn":"9781407034379","asin":null,"language":"English","explicit":false,"abridged":false},"coverPath":"/books/cover.jpg","tags":[],"numTracks":0,"numAudioFiles":0,"numChapters":0,"duration":0,"size":1001112,"ebookFormat":"epub"},"numFiles":3,"size":1177590}]}],"total":26,"limit":1,"page":0,"sortDesc":false,"minified":true,"include":""}"""
        val (_, client) = mockAbsClient(realJson to HttpStatusCode.OK)
        val result = client.getSeries(BASE_URL, "e77c113d-4383-488d-956f-89c18db431ac", "token", false)
        assertTrue(result is NetworkResult.Success, "Expected Success but got: $result")
        val series = (result as NetworkResult.Success).value
        assertEquals(1, series.size)
        assertEquals(series[0].name, "Discworld")
        assertEquals(1, series[0].bookCount)
        assertEquals(series[0].items[0].title, "The Colour Of Magic")
        assertEquals(EbookFormat.Epub, series[0].items[0].ebookFormat)
    }

    @Test
    fun `getSeries parses updatedAt from book items`() = runTest {
        val (_, client) = mockAbsClient(
            """{"results":[{"id":"ser-1","name":"Discworld","libraryId":"lib-1","books":[{"id":"item-1","libraryId":"lib-1","updatedAt":1762902014957,"media":{"metadata":{"title":"Colour of Magic","authorName":"Pratchett"},"ebookFormat":"epub"}}]}]}""" to HttpStatusCode.OK,
        )
        val result = client.getSeries(BASE_URL, "lib-1", "token", false)
        val item = (result as NetworkResult.Success).value[0].items[0]
        assertEquals(1762902014957L, item.updatedAt)
    }

    @Test
    fun `getSeries sets book updatedAt to null when absent`() = runTest {
        val (_, client) = mockAbsClient(
            """{"results":[{"id":"ser-1","name":"Discworld","libraryId":"lib-1","books":[{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"Colour of Magic","authorName":"Pratchett"},"ebookFormat":"epub"}}]}]}""" to HttpStatusCode.OK,
        )
        val result = client.getSeries(BASE_URL, "lib-1", "token", false)
        val item = (result as NetworkResult.Success).value[0].items[0]
        assertNull(item.updatedAt)
    }

    @Test
    fun `getSeries returns NetworkError on unreachable host`() = runTest {
        val client = offlineAbsClient()
        val result = client.getSeries("http://127.0.0.1:1", "lib-1", "token", false)
        assertTrue(result is NetworkResult.Offline)
    }

    // ── getCollections ───────────────────────────────────────────────────────

    @Test
    fun `getCollections parses collection list from API response`() = runTest {
        val (_, client) = mockAbsClient(
            ("""{"results":[{"id":"col-1","name":"My Collection","libraryId":"lib-1","books":[""" +
                """{"id":"item-1","libraryId":"lib-1","media":{"metadata":{"title":"Book One","authorName":"Author A"},"ebookFormat":"epub"}},""" +
                """{"id":"item-2","libraryId":"lib-1","media":{"metadata":{"title":"Book Two","authorName":"Author B"}}}""" +
                """]}]}""") to HttpStatusCode.OK,
        )
        val result = client.getCollections(BASE_URL, "lib-1", "token", false)
        assertTrue(result is NetworkResult.Success)
        val success = result as NetworkResult.Success
        assertEquals(1, success.value.size)
        assertEquals(success.value[0].id, "col-1")
        assertEquals(success.value[0].name, "My Collection")
        assertEquals(2, success.value[0].bookCount)
        assertEquals(EbookFormat.Epub, success.value[0].items[0].ebookFormat)
        assertEquals(EbookFormat.Unsupported, success.value[0].items[1].ebookFormat)
    }

    @Test
    fun `getCollections sends correct path and auth header`() = runTest {
        val (engine, client) = mockAbsClient("""{"results":[]}""" to HttpStatusCode.OK)
        client.getCollections(BASE_URL, "lib-42", "tok-abc", false)
        val req = engine.requestHistory[0]
        assertEquals(req.headers["Authorization"], "Bearer tok-abc")
        assertEquals(req.url.encodedPath, "/api/libraries/lib-42/collections")
        assertTrue(req.url.encodedPathAndQuery.contains("limit=500"))
    }

    @Test
    fun `getCollections returns NetworkError on unreachable host`() = runTest {
        val client = offlineAbsClient()
        val result = client.getCollections("http://127.0.0.1:1", "lib-1", "token", false)
        assertTrue(result is NetworkResult.Offline)
    }
}

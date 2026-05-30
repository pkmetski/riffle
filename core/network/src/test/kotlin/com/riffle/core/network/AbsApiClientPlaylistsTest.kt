package com.riffle.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AbsApiClientPlaylistsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: AbsApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = AbsApiClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun `getPlaylists issues GET with bearer token and parses response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"results":[{
                        "id":"pl-1","libraryId":"lib-1","name":"To Read","items":[
                          {"libraryItemId":"item-1","libraryItem":{
                            "id":"item-1","libraryId":"lib-1",
                            "media":{"metadata":{"title":"Book","authorName":"A"},"ebookFormat":"epub"}
                          }}
                        ]
                    }]}""".trimIndent()
                )
                .addHeader("Content-Type", "application/json")
        )

        val result = client.getPlaylists(baseUrl(), "lib-1", "tok", false)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/libraries/lib-1/playlists?limit=500", recorded.path)
        assertEquals("Bearer tok", recorded.getHeader("Authorization"))

        assertTrue(result is NetworkPlaylistResult.Success)
        val playlists = (result as NetworkPlaylistResult.Success).playlists
        assertEquals(1, playlists.size)
        assertEquals("pl-1", playlists[0].id)
        assertEquals("To Read", playlists[0].name)
        assertEquals(1, playlists[0].items.size)
        assertEquals("item-1", playlists[0].items[0].id)
    }

    @Test
    fun `getPlaylists includes bookIds even when libraryItem expansion is null`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"results":[{
                    "id":"pl-1","libraryId":"lib-1","name":"To Read","items":[
                      {"libraryItemId":"item-1","libraryItem":{
                        "id":"item-1","libraryId":"lib-1",
                        "media":{"metadata":{"title":"Book","authorName":"A"}}
                      }},
                      {"libraryItemId":"item-2","libraryItem":null}
                    ]
                }]}""".trimIndent()
            ).addHeader("Content-Type", "application/json")
        )

        val result = client.getPlaylists(baseUrl(), "lib-1", "tok", false)

        assertTrue(result is NetworkPlaylistResult.Success)
        val pl = (result as NetworkPlaylistResult.Success).playlists.single()
        assertEquals(setOf("item-1", "item-2"), pl.bookIds)
        assertEquals(listOf("item-1"), pl.items.map { it.id })
        assertEquals(2, pl.bookCount)
    }

    @Test
    fun `createPlaylist posts libraryId name and initial item`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"pl-1","libraryId":"lib-1","name":"To Read","items":[
                    {"libraryItemId":"item-1","libraryItem":{
                      "id":"item-1","libraryId":"lib-1",
                      "media":{"metadata":{"title":"T","authorName":"A"},"ebookFormat":"epub"}
                    }}
                ]}""".trimIndent()
            ).addHeader("Content-Type", "application/json")
        )

        val result = client.createPlaylist(baseUrl(), "lib-1", "To Read", "item-1", "tok", false)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/playlists", recorded.path)
        assertEquals("Bearer tok", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertEquals(
            """{"libraryId":"lib-1","name":"To Read","items":[{"libraryItemId":"item-1"}]}""",
            body
        )

        assertTrue(result is NetworkPlaylistWriteResult.Success)
        val playlist = (result as NetworkPlaylistWriteResult.Success).playlist
        assertNotNull(playlist)
        assertEquals("pl-1", playlist!!.id)
        assertEquals(1, playlist.items.size)
    }

    @Test
    fun `createPlaylist without initial book sends empty items array`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"pl-1","libraryId":"lib-1","name":"To Read","items":[]}"""
            ).addHeader("Content-Type", "application/json")
        )

        val result = client.createPlaylist(baseUrl(), "lib-1", "To Read", null, "tok", false)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/playlists", recorded.path)
        val body = recorded.body.readUtf8()
        assertEquals(
            """{"libraryId":"lib-1","name":"To Read","items":[]}""",
            body
        )
        assertTrue(result is NetworkPlaylistWriteResult.Success)
        val playlist = (result as NetworkPlaylistWriteResult.Success).playlist
        assertNotNull(playlist)
        assertTrue(playlist!!.items.isEmpty())
    }

    @Test
    fun `addBookToPlaylist posts libraryItemId to singular item endpoint`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"pl-1","libraryId":"lib-1","name":"To Read","items":[
                    {"libraryItemId":"item-1","libraryItem":{
                      "id":"item-1","libraryId":"lib-1",
                      "media":{"metadata":{"title":"T","authorName":"A"},"ebookFormat":"epub"}
                    }}
                ]}""".trimIndent()
            ).addHeader("Content-Type", "application/json")
        )

        val result = client.addBookToPlaylist(baseUrl(), "pl-1", "item-1", "tok", false)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/playlists/pl-1/item", recorded.path)
        assertEquals("Bearer tok", recorded.getHeader("Authorization"))
        assertEquals("""{"libraryItemId":"item-1"}""", recorded.body.readUtf8())

        assertTrue(result is NetworkPlaylistWriteResult.Success)
        val playlist = (result as NetworkPlaylistWriteResult.Success).playlist
        assertNotNull(playlist)
        assertEquals("pl-1", playlist!!.id)
    }

    @Test
    fun `removeBookFromPlaylist deletes singular item path`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"pl-1","libraryId":"lib-1","name":"To Read","items":[]}"""
            ).addHeader("Content-Type", "application/json")
        )

        val result = client.removeBookFromPlaylist(baseUrl(), "pl-1", "item-1", "tok", false)

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/playlists/pl-1/item/item-1", recorded.path)
        assertEquals("Bearer tok", recorded.getHeader("Authorization"))

        assertTrue(result is NetworkPlaylistWriteResult.Success)
    }
}

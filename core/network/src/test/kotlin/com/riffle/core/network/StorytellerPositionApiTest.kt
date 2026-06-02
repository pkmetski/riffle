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

class StorytellerPositionApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: StorytellerPositionApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        api = StorytellerPositionApiImpl(OkHttpClient())
    }

    @After fun tearDown() = server.shutdown()

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test fun getPosition_parsesLocatorAndTimestamp() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"userId":"u","bookUuid":"b","locator":{"href":"text/c1.html","locations":{"fragments":["id1-s2"],"progression":0.5,"totalProgression":0.1}},"timestamp":1780258583061}""",
            ),
        )

        val result = api.getPosition(baseUrl(), "42", "tkn", insecureAllowed = false)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v2/books/42/positions", recorded.path)
        assertEquals("Bearer tkn", recorded.getHeader("Authorization"))
        assertTrue(result is NetworkStorytellerPositionResult.Success)
        result as NetworkStorytellerPositionResult.Success
        assertEquals(1780258583061L, result.timestampMillis)
        assertTrue("locator json carries the href", result.locatorJson.contains("text/c1.html"))
        assertTrue("locator json carries the fragment", result.locatorJson.contains("id1-s2"))
    }

    @Test fun getPosition_404_isNoPosition() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = api.getPosition(baseUrl(), "42", "tkn", insecureAllowed = false)

        assertTrue(result is NetworkStorytellerPositionResult.NoPosition)
    }

    @Test fun getPosition_serverError_isNetworkError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = api.getPosition(baseUrl(), "42", "tkn", insecureAllowed = false)

        assertTrue(result is NetworkStorytellerPositionResult.NetworkError)
    }

    @Test fun putPosition_sendsLocatorAndTimestamp() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = api.putPosition(
            baseUrl(), "42",
            locatorJson = """{"href":"text/c1.html","locations":{"progression":0.5}}""",
            timestampMillis = 1780258583061L,
            token = "tkn",
            insecureAllowed = false,
        )

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/v2/books/42/positions", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue("body carries locator", body.contains("text/c1.html"))
        assertTrue("body carries timestamp", body.contains("1780258583061"))
        assertTrue(result is NetworkStorytellerPutResult.Success)
    }
}

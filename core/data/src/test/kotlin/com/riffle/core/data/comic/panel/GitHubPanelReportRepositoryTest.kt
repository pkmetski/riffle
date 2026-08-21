package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.PanelDetectionFailureType
import com.riffle.core.domain.comic.panel.PanelDetectionReport
import com.riffle.core.domain.comic.panel.PanelRegion
import com.riffle.core.domain.comic.panel.PanelSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubPanelReportRepositoryTest {

    private val server = MockWebServer()
    private lateinit var httpClient: HttpClient

    @Before
    fun setUp() {
        server.start()
        httpClient = HttpClient(OkHttp)
    }

    @After
    fun tearDown() {
        httpClient.close()
        server.shutdown()
    }

    private fun repo(): GitHubPanelReportRepository {
        val baseUrl = server.url("/").toString().trimEnd('/')
        return GitHubPanelReportRepository(
            pat = "test-pat",
            owner = "pkmetski",
            repoName = "riffle",
            client = httpClient,
            apiBase = baseUrl,
            rawBase = baseUrl,
        )
    }

    private val fakeReport = PanelDetectionReport(
        bookId = "test-book",
        pageIndex = 3,
        imageWidth = 800,
        imageHeight = 1200,
        detectedPanels = listOf(PanelRegion(10, 10, 200, 300)),
        detectedSource = PanelSource.Auto,
        failureType = PanelDetectionFailureType.MergedPanels,
        notes = "Top two panels merged",
        tappedX = 50,
        tappedY = 50,
        tappedPanelIndex = 0,
    )

    @Test
    fun `submit makes 7 API calls and returns issue URL`() = runTest {
        // 1. create blob
        server.enqueue(MockResponse().setBody("""{"sha":"blob-sha-123"}""").setResponseCode(201))
        // 2. get ref (current commit sha)
        server.enqueue(MockResponse().setBody("""{"object":{"sha":"commit-abc"}}""").setResponseCode(200))
        // 3. get commit (tree sha)
        server.enqueue(MockResponse().setBody("""{"tree":{"sha":"tree-xyz"}}""").setResponseCode(200))
        // 4. create tree
        server.enqueue(MockResponse().setBody("""{"sha":"new-tree-sha"}""").setResponseCode(201))
        // 5. create commit
        server.enqueue(MockResponse().setBody("""{"sha":"new-commit-sha"}""").setResponseCode(201))
        // 6. patch ref
        server.enqueue(MockResponse().setBody("""{"ref":"refs/heads/panel-reports"}""").setResponseCode(200))
        // 7. create issue
        server.enqueue(MockResponse().setBody("""{"html_url":"https://github.com/pkmetski/riffle/issues/99"}""").setResponseCode(201))

        val result = repo().submit(fakeReport, ByteArray(5) { it.toByte() })

        assertTrue(result.isSuccess)
        assertEquals("https://github.com/pkmetski/riffle/issues/99", result.getOrNull())
        assertEquals(7, server.requestCount)

        // Verify issue body contains failure type and notes (7th request = create issue)
        val requests = (1..7).map { server.takeRequest() }
        val issueBody = requests[6].body.readUtf8()
        val parsedBody = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(issueBody).jsonObject["body"]?.jsonPrimitive?.content ?: ""
        assertTrue("body contains failure type", parsedBody.contains("Merged panels"))
        assertTrue("body contains notes", parsedBody.contains("Top two panels merged"))
    }

    @Test
    fun `issue body contains expected panel order when WrongPanelOrder report submitted`() = runTest {
        server.enqueue(MockResponse().setBody("""{"sha":"blob-sha-123"}""").setResponseCode(201))
        server.enqueue(MockResponse().setBody("""{"object":{"sha":"commit-abc"}}""").setResponseCode(200))
        server.enqueue(MockResponse().setBody("""{"tree":{"sha":"tree-xyz"}}""").setResponseCode(200))
        server.enqueue(MockResponse().setBody("""{"sha":"new-tree-sha"}""").setResponseCode(201))
        server.enqueue(MockResponse().setBody("""{"sha":"new-commit-sha"}""").setResponseCode(201))
        server.enqueue(MockResponse().setBody("""{"ref":"refs/heads/panel-reports"}""").setResponseCode(200))
        server.enqueue(MockResponse().setBody("""{"html_url":"https://github.com/pkmetski/riffle/issues/99"}""").setResponseCode(201))

        val reportWithOrder = fakeReport.copy(
            failureType = PanelDetectionFailureType.WrongPanelOrder,
            expectedPanelOrder = listOf(1, 0),
        )
        repo().submit(reportWithOrder, ByteArray(0))

        val requests = (1..7).map { server.takeRequest() }
        val issueBody = requests[6].body.readUtf8()
        val parsedBody = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(issueBody).jsonObject["body"]?.jsonPrimitive?.content ?: ""
        assertTrue("body contains expected order", parsedBody.contains("[1, 0]"))
    }

    @Test
    fun `submit returns failure when API call fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Bad credentials"}"""))

        val result = repo().submit(fakeReport, ByteArray(0))

        assertTrue(result.isFailure)
    }

    @Test
    fun `submit retries on 422 not-a-fast-forward and succeeds on second attempt`() = runTest {
        // 1. create blob
        server.enqueue(MockResponse().setBody("""{"sha":"blob-sha-123"}""").setResponseCode(201))
        // --- attempt 1 ---
        // 2. get ref
        server.enqueue(MockResponse().setBody("""{"object":{"sha":"stale-commit"}}""").setResponseCode(200))
        // 3. get commit
        server.enqueue(MockResponse().setBody("""{"tree":{"sha":"stale-tree"}}""").setResponseCode(200))
        // 4. create tree
        server.enqueue(MockResponse().setBody("""{"sha":"new-tree-1"}""").setResponseCode(201))
        // 5. create commit
        server.enqueue(MockResponse().setBody("""{"sha":"new-commit-1"}""").setResponseCode(201))
        // 6. patch ref → 422
        server.enqueue(MockResponse().setBody("""{"message":"Update is not a fast forward"}""").setResponseCode(422))
        // --- attempt 2 ---
        // 2. get ref (now returns fresh sha)
        server.enqueue(MockResponse().setBody("""{"object":{"sha":"fresh-commit"}}""").setResponseCode(200))
        // 3. get commit
        server.enqueue(MockResponse().setBody("""{"tree":{"sha":"fresh-tree"}}""").setResponseCode(200))
        // 4. create tree
        server.enqueue(MockResponse().setBody("""{"sha":"new-tree-2"}""").setResponseCode(201))
        // 5. create commit
        server.enqueue(MockResponse().setBody("""{"sha":"new-commit-2"}""").setResponseCode(201))
        // 6. patch ref → success
        server.enqueue(MockResponse().setBody("""{"ref":"refs/heads/panel-reports"}""").setResponseCode(200))
        // 7. create issue
        server.enqueue(MockResponse().setBody("""{"html_url":"https://github.com/pkmetski/riffle/issues/42"}""").setResponseCode(201))

        val result = repo().submit(fakeReport, ByteArray(0))

        assertTrue(result.isSuccess)
        assertEquals("https://github.com/pkmetski/riffle/issues/42", result.getOrNull())
    }
}

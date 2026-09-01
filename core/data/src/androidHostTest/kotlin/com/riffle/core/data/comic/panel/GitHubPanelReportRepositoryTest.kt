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

    private fun gistResponse(gistUrl: String, maskRawUrl: String): String =
        """{"html_url":"$gistUrl","files":{"mask.b64":{"raw_url":"$maskRawUrl"}}}"""

    @Test
    fun `submit makes 2 API calls and returns issue URL`() = runTest {
        // 1. create gist
        server.enqueue(MockResponse().setBody(
            gistResponse("https://gist.github.com/pkmetski/abc123", "https://gist.githubusercontent.com/raw/mask.b64")
        ).setResponseCode(201))
        // 2. create issue
        server.enqueue(MockResponse().setBody("""{"html_url":"https://github.com/pkmetski/riffle/issues/99"}""").setResponseCode(201))

        val result = repo().submit(fakeReport, ByteArray(5) { it.toByte() })

        assertTrue(result.isSuccess)
        assertEquals("https://github.com/pkmetski/riffle/issues/99", result.getOrNull())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `gist body contains base64-encoded mask and metadata`() = runTest {
        server.enqueue(MockResponse().setBody(
            gistResponse("https://gist.github.com/pkmetski/abc123", "https://gist.githubusercontent.com/raw/mask.b64")
        ).setResponseCode(201))
        server.enqueue(MockResponse().setBody("""{"html_url":"https://github.com/pkmetski/riffle/issues/99"}""").setResponseCode(201))

        val maskBytes = ByteArray(5) { it.toByte() }
        repo().submit(fakeReport, maskBytes)

        val gistRequest = server.takeRequest()
        val gistBody = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(gistRequest.body.readUtf8()).jsonObject
        val expectedBase64 = java.util.Base64.getEncoder().encodeToString(maskBytes)
        val maskContent = gistBody["files"]!!.jsonObject["mask.b64"]!!
            .jsonObject["content"]!!.jsonPrimitive.content
        assertEquals(expectedBase64, maskContent)
        // metadata file present
        val metadata = gistBody["files"]!!.jsonObject["metadata.json"]!!
            .jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("metadata contains pageIndex", metadata.contains("3"))
        assertTrue("metadata contains failureType", metadata.contains("Merged panels"))
    }

    @Test
    fun `issue body contains gist URL and mask raw URL`() = runTest {
        val gistHtmlUrl = "https://gist.github.com/pkmetski/abc123"
        val maskRawUrl = "https://gist.githubusercontent.com/raw/mask.b64"
        server.enqueue(MockResponse().setBody(
            gistResponse(gistHtmlUrl, maskRawUrl)
        ).setResponseCode(201))
        server.enqueue(MockResponse().setBody("""{"html_url":"https://github.com/pkmetski/riffle/issues/99"}""").setResponseCode(201))

        repo().submit(fakeReport, ByteArray(0))

        server.takeRequest() // consume gist
        val issueRequest = server.takeRequest()
        val parsedBody = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(issueRequest.body.readUtf8()).jsonObject["body"]?.jsonPrimitive?.content ?: ""
        assertTrue("body contains gist URL", parsedBody.contains(gistHtmlUrl))
        assertTrue("body contains mask raw URL", parsedBody.contains(maskRawUrl))
        assertTrue("body contains failure type", parsedBody.contains("Merged panels"))
        assertTrue("body contains notes", parsedBody.contains("Top two panels merged"))
    }

    @Test
    fun `issue body contains expected panel order when WrongPanelOrder report submitted`() = runTest {
        server.enqueue(MockResponse().setBody(
            gistResponse("https://gist.github.com/pkmetski/abc123", "https://gist.githubusercontent.com/raw/mask.b64")
        ).setResponseCode(201))
        server.enqueue(MockResponse().setBody("""{"html_url":"https://github.com/pkmetski/riffle/issues/99"}""").setResponseCode(201))

        val reportWithOrder = fakeReport.copy(
            failureType = PanelDetectionFailureType.WrongPanelOrder,
            expectedPanelOrder = listOf(1, 0),
        )
        repo().submit(reportWithOrder, ByteArray(0))

        server.takeRequest() // consume gist
        val issueRequest = server.takeRequest()
        val parsedBody = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(issueRequest.body.readUtf8()).jsonObject["body"]?.jsonPrimitive?.content ?: ""
        assertTrue("body contains expected order", parsedBody.contains("[1, 0]"))
    }

    @Test
    fun `gist metadata contains false panel indices when FalsePanel report submitted`() = runTest {
        server.enqueue(MockResponse().setBody(
            gistResponse("https://gist.github.com/pkmetski/abc123", "https://gist.githubusercontent.com/raw/mask.b64")
        ).setResponseCode(201))
        server.enqueue(MockResponse().setBody("""{"html_url":"https://github.com/pkmetski/riffle/issues/99"}""").setResponseCode(201))

        val reportWithFalsePanels = fakeReport.copy(
            failureType = PanelDetectionFailureType.FalsePanel,
            falsePanelIndices = listOf(0, 2),
        )
        repo().submit(reportWithFalsePanels, ByteArray(0))

        val gistRequest = server.takeRequest()
        val gistBody = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(gistRequest.body.readUtf8()).jsonObject
        val metadata = gistBody["files"]!!.jsonObject["metadata.json"]!!
            .jsonObject["content"]!!.jsonPrimitive.content
        assertTrue("metadata contains falsePanelIndices", metadata.contains("falsePanelIndices"))
        assertTrue("metadata contains panel 0", metadata.contains("0"))
        assertTrue("metadata contains panel 2", metadata.contains("2"))
    }

    @Test
    fun `issue body contains false panel indices when FalsePanel report submitted`() = runTest {
        server.enqueue(MockResponse().setBody(
            gistResponse("https://gist.github.com/pkmetski/abc123", "https://gist.githubusercontent.com/raw/mask.b64")
        ).setResponseCode(201))
        server.enqueue(MockResponse().setBody("""{"html_url":"https://github.com/pkmetski/riffle/issues/99"}""").setResponseCode(201))

        val reportWithFalsePanels = fakeReport.copy(
            failureType = PanelDetectionFailureType.FalsePanel,
            falsePanelIndices = listOf(0, 2),
        )
        repo().submit(reportWithFalsePanels, ByteArray(0))

        server.takeRequest() // consume gist
        val issueRequest = server.takeRequest()
        val parsedBody = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(issueRequest.body.readUtf8()).jsonObject["body"]?.jsonPrimitive?.content ?: ""
        assertTrue("body contains false panel indices", parsedBody.contains("[0, 2]"))
    }

    @Test
    fun `submit returns failure when API call fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Bad credentials"}"""))

        val result = repo().submit(fakeReport, ByteArray(0))

        assertTrue(result.isFailure)
    }
}

package com.riffle.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitHubReleaseApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GitHubReleaseApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val baseUrl = server.url("/").toString().trimEnd('/')
        api = GitHubReleaseApi(OkHttpClient(), apiBaseUrl = baseUrl)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `latestRelease picks the apk asset`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "tag_name": "v1.5.0",
                    "draft": false,
                    "prerelease": false,
                    "assets": [
                      { "name": "mapping.txt", "browser_download_url": "https://x/mapping.txt", "size": 10 },
                      { "name": "riffle-1.5.0.apk", "browser_download_url": "https://x/riffle.apk", "size": 4200 }
                    ]
                  }
                ]
                """.trimIndent()
            )
        )

        val result = api.latestRelease("pkmetski/riffle")

        assertTrue(result is GitHubReleaseResult.Success)
        val release = (result as GitHubReleaseResult.Success).release
        assertEquals("v1.5.0", release.tagName)
        assertEquals("https://x/riffle.apk", release.apkUrl)
        assertEquals(4200L, release.apkSizeBytes)
    }

    @Test
    fun `latestRelease skips a still-building release and falls back to the prior apk release`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "tag_name": "v1.6.0",
                    "draft": false,
                    "prerelease": false,
                    "assets": []
                  },
                  {
                    "tag_name": "v1.5.0",
                    "draft": false,
                    "prerelease": false,
                    "assets": [
                      { "name": "riffle-1.5.0.apk", "browser_download_url": "https://x/riffle.apk", "size": 4200 }
                    ]
                  }
                ]
                """.trimIndent()
            )
        )

        val result = api.latestRelease("pkmetski/riffle")

        assertTrue(result is GitHubReleaseResult.Success)
        assertEquals("v1.5.0", (result as GitHubReleaseResult.Success).release.tagName)
    }

    @Test
    fun `latestRelease ignores drafts and prereleases`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "tag_name": "v2.0.0-rc1",
                    "draft": false,
                    "prerelease": true,
                    "assets": [
                      { "name": "riffle-2.0.0-rc1.apk", "browser_download_url": "https://x/rc.apk", "size": 1 }
                    ]
                  },
                  {
                    "tag_name": "v1.9.0-draft",
                    "draft": true,
                    "prerelease": false,
                    "assets": [
                      { "name": "riffle-1.9.0.apk", "browser_download_url": "https://x/draft.apk", "size": 1 }
                    ]
                  },
                  {
                    "tag_name": "v1.5.0",
                    "draft": false,
                    "prerelease": false,
                    "assets": [
                      { "name": "riffle-1.5.0.apk", "browser_download_url": "https://x/riffle.apk", "size": 4200 }
                    ]
                  }
                ]
                """.trimIndent()
            )
        )

        val result = api.latestRelease("pkmetski/riffle")

        assertTrue(result is GitHubReleaseResult.Success)
        assertEquals("v1.5.0", (result as GitHubReleaseResult.Success).release.tagName)
    }

    @Test
    fun `latestRelease fails when no release has an apk asset`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "tag_name": "v1.5.0",
                    "draft": false,
                    "prerelease": false,
                    "assets": [
                      { "name": "notes.txt", "browser_download_url": "https://x/n", "size": 1 }
                    ]
                  }
                ]
                """.trimIndent()
            )
        )

        assertTrue(api.latestRelease("pkmetski/riffle") is GitHubReleaseResult.Failed)
    }

    @Test
    fun `latestRelease fails on an error response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        assertTrue(api.latestRelease("pkmetski/riffle") is GitHubReleaseResult.Failed)
    }

    @Test
    fun `download writes the body and reports progress`() = runTest {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        val dest = File(Files.createTempDirectory("upd").toFile(), "riffle.apk")
        val seen = mutableListOf<Int>()

        val ok = api.download(server.url("/riffle.apk").toString(), dest) { seen.add(it) }

        assertTrue(ok)
        assertEquals(payload.size.toLong(), dest.length())
        assertTrue(dest.readBytes().contentEquals(payload))
        assertTrue("expected progress callbacks", seen.isNotEmpty())
        assertEquals(100, seen.last())
    }

    @Test
    fun `download deletes a partial file on an error response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val dest = File(Files.createTempDirectory("upd").toFile(), "riffle.apk")

        val ok = api.download(server.url("/riffle.apk").toString(), dest) {}

        assertFalse(ok)
        assertFalse(dest.exists())
    }
}

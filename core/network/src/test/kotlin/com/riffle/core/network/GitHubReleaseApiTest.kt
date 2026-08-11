package com.riffle.core.network

import com.riffle.core.network.createDefaultHttpClient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.TimeUnit

class GitHubReleaseApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GitHubReleaseApi

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val baseUrl = server.url("/").toString().trimEnd('/')
        api = GitHubReleaseApi(createDefaultHttpClient(OkHttpClient()), apiBaseUrl = baseUrl)
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
                    ).addHeader("Content-Type", "application/json")
        )

        val result = api.latestRelease("pkmetski/riffle")

        assertTrue(result is GitHubReleaseResult.Success)
        val release = (result as GitHubReleaseResult.Success).release
        assertEquals("v1.5.0", release.tagName)
        assertEquals("https://x/riffle.apk", release.apkUrl)
        assertEquals(4200L, release.apkSizeBytes)
    }

    @Test
    fun `latestRelease accepts legacy app release apk assets`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "tag_name": "v1.4.0",
                    "draft": false,
                    "prerelease": false,
                    "assets": [
                      { "name": "app-release.apk", "browser_download_url": "https://x/app-release.apk", "size": 4100 }
                    ]
                  }
                ]
                """.trimIndent()
            ).addHeader("Content-Type", "application/json")
        )

        val result = api.latestRelease("pkmetski/riffle")

        assertTrue(result is GitHubReleaseResult.Success)
        val release = (result as GitHubReleaseResult.Success).release
        assertEquals("v1.4.0", release.tagName)
        assertEquals("https://x/app-release.apk", release.apkUrl)
        assertEquals(4100L, release.apkSizeBytes)
    }

    @Test
    fun `latestRelease prefers the versioned riffle apk over legacy apk assets`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {
                    "tag_name": "v1.7.0",
                    "draft": false,
                    "prerelease": false,
                    "assets": [
                      { "name": "app-release.apk", "browser_download_url": "https://x/legacy.apk", "size": 4100 },
                      { "name": "riffle-1.7.0.apk", "browser_download_url": "https://x/riffle-1.7.0.apk", "size": 4700 }
                    ]
                  }
                ]
                """.trimIndent()
            ).addHeader("Content-Type", "application/json")
        )

        val result = api.latestRelease("pkmetski/riffle")

        assertTrue(result is GitHubReleaseResult.Success)
        val release = (result as GitHubReleaseResult.Success).release
        assertEquals("https://x/riffle-1.7.0.apk", release.apkUrl)
        assertEquals(4700L, release.apkSizeBytes)
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
                    ).addHeader("Content-Type", "application/json")
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
                    ).addHeader("Content-Type", "application/json")
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
                    ).addHeader("Content-Type", "application/json")
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
    fun `download reports progress before the response finishes`() = runBlocking {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(payload))
                .throttleBody(64 * 1024, 1, TimeUnit.SECONDS)
        )
        val dest = File(Files.createTempDirectory("upd").toFile(), "riffle.apk")
        val firstProgress = kotlinx.coroutines.CompletableDeferred<Int>()

        val download = async(Dispatchers.IO) {
            api.download(server.url("/riffle.apk").toString(), dest) { percent ->
                firstProgress.complete(percent)
            }
        }

        val percentWhileResponseIsInFlight = withTimeout(1_500) { firstProgress.await() }

        assertTrue(percentWhileResponseIsInFlight in 1..99)
        assertFalse("download should still be receiving the throttled response", download.isCompleted)
        assertTrue(download.await())
    }

    // Regression: GitHub CDN can serve APK assets without Content-Length (chunked transfer), leaving
    // total == -1 and suppressing all progress callbacks — the dialog stays frozen at 0% even though
    // the download completes. When the caller provides expectedBytes the fallback kicks in so progress
    // advances normally.
    @Test
    fun `download reports progress via expectedBytes when Content-Length is absent`() = runTest {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(payload), 8192))
        val dest = File(Files.createTempDirectory("upd").toFile(), "riffle.apk")
        val seen = mutableListOf<Int>()

        val ok = api.download(
            server.url("/riffle.apk").toString(),
            dest,
            expectedBytes = payload.size.toLong(),
        ) { seen.add(it) }

        assertTrue(ok)
        assertEquals(payload.size.toLong(), dest.length())
        assertTrue("expected progress callbacks with expectedBytes fallback", seen.isNotEmpty())
        assertEquals(100, seen.last())
    }

    @Test
    fun `download reports no progress when both Content-Length and expectedBytes are absent`() = runTest {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(payload), 8192))
        val dest = File(Files.createTempDirectory("upd").toFile(), "riffle.apk")
        val seen = mutableListOf<Int>()

        val ok = api.download(server.url("/riffle.apk").toString(), dest) { seen.add(it) }

        assertTrue(ok)
        assertEquals(payload.size.toLong(), dest.length())
        assertTrue("no progress expected without Content-Length or expectedBytes", seen.isEmpty())
    }

    @Test
    fun `download deletes a partial file on an error response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val dest = File(Files.createTempDirectory("upd").toFile(), "riffle.apk")

        val ok = api.download(server.url("/riffle.apk").toString(), dest) {}

        assertFalse(ok)
        assertFalse(dest.exists())
    }

    @Test
    fun `listReleases returns all non-draft non-prerelease entries with bodies`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  { "tag_name": "v1.6.0", "draft": false, "prerelease": false, "body": "### What's new\n- Feature A",
                    "published_at": "2026-07-28T21:14:00Z",
                    "assets": [{ "name": "riffle-1.6.0.apk", "browser_download_url": "https://x/1.6.0.apk", "size": 5000 }] },
                  { "tag_name": "v1.5.0-rc1", "draft": false, "prerelease": true, "body": "RC notes",
                    "assets": [{ "name": "riffle-1.5.0-rc1.apk", "browser_download_url": "https://x/rc.apk", "size": 1 }] },
                  { "tag_name": "v1.5.0", "draft": false, "prerelease": false, "body": "### Fixes\n- Bug fix",
                    "assets": [{ "name": "riffle-1.5.0.apk", "browser_download_url": "https://x/1.5.0.apk", "size": 4200 }] },
                  { "tag_name": "v1.4.0-draft", "draft": true, "prerelease": false, "body": "Draft", "published_at": null,
                    "assets": [] }
                ]
                """.trimIndent()
            ).addHeader("Content-Type", "application/json")
        )

        val releases = api.listReleases("pkmetski/riffle")

        assertEquals(2, releases.size)
        assertEquals("v1.6.0", releases[0].tagName)
        assertEquals("### What's new\n- Feature A", releases[0].body)
        assertEquals("https://x/1.6.0.apk", releases[0].apkUrl)
        assertEquals(5000L, releases[0].apkSizeBytes)
        assertEquals("2026-07-28T21:14:00Z", releases[0].publishedAt)
        assertEquals("v1.5.0", releases[1].tagName)
        assertEquals("### Fixes\n- Bug fix", releases[1].body)
    }

    @Test
    fun `listReleases keeps download metadata for legacy app release apk assets`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  { "tag_name": "v1.4.0", "draft": false, "prerelease": false, "body": "Notes",
                    "assets": [{ "name": "app-release.apk", "browser_download_url": "https://x/app-release.apk", "size": 4100 }] }
                ]
                """.trimIndent()
            ).addHeader("Content-Type", "application/json")
        )

        val releases = api.listReleases("pkmetski/riffle")

        assertEquals(1, releases.size)
        assertEquals("https://x/app-release.apk", releases[0].apkUrl)
        assertEquals(4100L, releases[0].apkSizeBytes)
    }

    @Test
    fun `listReleases returns empty list when response is empty`() = runTest {
        server.enqueue(MockResponse().setBody("[]").addHeader("Content-Type", "application/json"))
        assertEquals(emptyList<GitHubRelease>(), api.listReleases("pkmetski/riffle"))
    }

    @Test
    fun `listReleases returns empty list on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("error"))
        assertEquals(emptyList<GitHubRelease>(), api.listReleases("pkmetski/riffle"))
    }

    @Test
    fun `listReleases includes releases without an apk asset with empty url`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  { "tag_name": "v1.6.0", "draft": false, "prerelease": false, "body": "Notes",
                    "assets": [] }
                ]
                """.trimIndent()
            ).addHeader("Content-Type", "application/json")
        )

        val releases = api.listReleases("pkmetski/riffle")

        assertEquals(1, releases.size)
        assertEquals("", releases[0].apkUrl)
        assertEquals(0L, releases[0].apkSizeBytes)
        assertEquals("Notes", releases[0].body)
    }

    // Regression: verified on AVD that GitHub sends `Cache-Control: max-age=60` on its releases
    // response, so without FORCE_NETWORK on the request the shared default OkHttp cache serves the
    // previous response for up to 60s — silently no-op'ing a re-tap of the Settings "Check for
    // updates" button. The manual button's contract is "check now", so every call must bypass any
    // cached copy. If someone drops the .cacheControl(FORCE_NETWORK) line, this test flips red.
    @Test
    fun `latestRelease request opts out of the cache with no-cache no-store`() = runTest {
        server.enqueue(MockResponse().setBody("[]").addHeader("Content-Type", "application/json"))

        api.latestRelease("pkmetski/riffle")

        val recorded = server.takeRequest()
        val cc = recorded.getHeader("Cache-Control").orEmpty()
        assertTrue(
            "expected FORCE_NETWORK (no-cache) on the request, got '$cc'",
            cc.contains("no-cache")
        )
    }
}

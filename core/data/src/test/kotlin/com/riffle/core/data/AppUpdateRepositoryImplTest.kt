package com.riffle.core.data

import com.riffle.core.data.AppUpdateRepositoryImpl.Companion.evaluate
import com.riffle.core.data.AppUpdateRepositoryImpl.Companion.listReleasesSince
import com.riffle.core.data.AppUpdateRepositoryImpl.Companion.versionCodeOf
import com.riffle.core.domain.UpdateCheckResult
import com.riffle.core.network.GitHubRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryImplTest {

    @Test
    fun `versionCodeOf mirrors the release workflow formula`() {
        assertEquals(10400, versionCodeOf("1.4.0"))
        assertEquals(10203, versionCodeOf("1.2.3"))
        assertEquals(0, versionCodeOf("0.0.0"))
        assertEquals(120015, versionCodeOf("12.0.15"))
    }

    @Test
    fun `versionCodeOf rejects malformed tags`() {
        assertNull(versionCodeOf("1.4"))
        assertNull(versionCodeOf("1.4.0-rc1"))
        assertNull(versionCodeOf("v1.4.0"))
        assertNull(versionCodeOf("latest"))
        assertNull(versionCodeOf(""))
    }

    @Test
    fun `evaluate reports an update when the release is ahead`() {
        val release = GitHubRelease("v1.5.0", "https://example/riffle.apk", 4_200L)

        val result = evaluate(currentVersionCode = 10400, release = release)

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val update = (result as UpdateCheckResult.UpdateAvailable).update
        assertEquals("1.5.0", update.versionName)
        assertEquals(10500, update.versionCode)
        assertEquals("https://example/riffle.apk", update.downloadUrl)
        assertEquals(4_200L, update.sizeBytes)
    }

    @Test
    fun `evaluate reports up-to-date when the installed build matches or leads`() {
        val release = GitHubRelease("v1.4.0", "https://example/riffle.apk", 1L)

        assertEquals(UpdateCheckResult.UpToDate, evaluate(10400, release))
        // A local dev build can carry a higher code than any release; never offer a downgrade.
        assertEquals(UpdateCheckResult.UpToDate, evaluate(99999, release))
    }

    @Test
    fun `evaluate fails on an unparseable tag`() {
        val release = GitHubRelease("nightly", "https://example/riffle.apk", 1L)

        val result = evaluate(10400, release)

        assertTrue(result is UpdateCheckResult.Failed)
    }

    // --- listReleasesSince ---

    private fun release(tag: String, body: String = "", apkUrl: String = "https://x/$tag.apk", size: Long = 1000L) =
        GitHubRelease(tagName = tag, apkUrl = apkUrl, apkSizeBytes = size, body = body)

    @Test
    fun `listReleasesSince returns only releases newer than sinceVersionCode`() {
        val releases = listOf(
            release("v1.6.0", "Notes 1.6"),
            release("v1.5.0", "Notes 1.5"),
            release("v1.4.0", "Notes 1.4"),
        )

        val result = listReleasesSince(releases, sinceVersionCode = 10500)

        assertEquals(1, result.size)
        assertEquals("1.6.0", result[0].versionName)
        assertEquals(10600, result[0].versionCode)
        assertEquals("Notes 1.6", result[0].changelog)
        assertEquals("https://x/v1.6.0.apk", result[0].downloadUrl)
        assertEquals(1000L, result[0].sizeBytes)
    }

    @Test
    fun `listReleasesSince with sinceVersionCode 0 returns all parseable releases`() {
        val releases = listOf(
            release("v1.5.0", "Notes 1.5"),
            release("v1.4.0", "Notes 1.4"),
        )

        val result = listReleasesSince(releases, sinceVersionCode = 0)

        assertEquals(2, result.size)
    }

    @Test
    fun `listReleasesSince skips releases with unparseable tags`() {
        val releases = listOf(
            release("nightly"),
            release("v1.5.0", "Notes"),
        )

        val result = listReleasesSince(releases, sinceVersionCode = 0)

        assertEquals(1, result.size)
        assertEquals("1.5.0", result[0].versionName)
    }

    @Test
    fun `listReleasesSince returns empty when nothing is newer`() {
        val releases = listOf(release("v1.4.0"))

        val result = listReleasesSince(releases, sinceVersionCode = 10500)

        assertTrue(result.isEmpty())
    }
}

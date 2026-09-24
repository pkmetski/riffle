package com.riffle.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateVersionsTest {

    // --- versionCodeOf ---

    @Test
    fun versionCodeOfParsesStandardTag() {
        assertEquals(10203, AppUpdateVersions.versionCodeOf("1.2.3"))
    }

    @Test
    fun versionCodeOfReturnsNullForMalformedTag() {
        assertNull(AppUpdateVersions.versionCodeOf("not-a-version"))
    }

    // --- badge stripping in listReleasesSince ---

    @Test
    fun badgeLinesAreStrippedFromChangelog() {
        val releases = listOf(
            ReleaseCandidate(
                tagName = "v1.0.1",
                downloadUrl = "",
                sizeBytes = 0L,
                body = "[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/pkmetski)\n\nReal release notes.",
            )
        )
        val result = AppUpdateVersions.listReleasesSince(releases, sinceVersionCode = 0)
        assertEquals("Real release notes.", result.single().changelog)
    }

    @Test
    fun changelogWithNoBadgeIsUnchanged() {
        val releases = listOf(
            ReleaseCandidate(
                tagName = "v1.0.1",
                downloadUrl = "",
                sizeBytes = 0L,
                body = "Bug fixes and improvements.",
            )
        )
        val result = AppUpdateVersions.listReleasesSince(releases, sinceVersionCode = 0)
        assertEquals("Bug fixes and improvements.", result.single().changelog)
    }

    @Test
    fun onlyBadgeLinesAreRemovedLeavingOtherMarkdown() {
        val body = """
            [![badge](https://img.shields.io/badge/foo-bar.svg)](https://example.com)

            ## What's new

            - Fixed a crash.
        """.trimIndent()
        val releases = listOf(
            ReleaseCandidate(tagName = "v1.0.1", downloadUrl = "", sizeBytes = 0L, body = body)
        )
        val result = AppUpdateVersions.listReleasesSince(releases, sinceVersionCode = 0)
        val changelog = result.single().changelog
        assertFalse(changelog.contains("[!["), "Badge line should have been stripped")
        assertTrue(changelog.contains("Fixed a crash"), "Real notes should be preserved")
    }
}

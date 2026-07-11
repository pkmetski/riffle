package com.riffle.app.feature.settings

import com.riffle.app.feature.settings.sections.readaloudRowSummary
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the collapsed Readaloud row's subtitle. The pre-collapse layout showed
 * per-row status (server host, version, match counts) inline; folding those rows behind a single
 * drill-in row must preserve that at-a-glance information — otherwise the collapse loses
 * information that used to be visible without a tap.
 *
 * Each assertion below would flip if the subtitle stopped reporting one of: not-configured hint,
 * host + version, or match counts.
 */
class ReadaloudRowSummaryTest {

    private fun storytellerSource(username: String = ""): Source = Source(
        id = "sty-1",
        url = SourceUrl.parse("https://storyteller.example.com")!!,
        isActive = false,
        insecureConnectionAllowed = false,
        username = username,
        serverType = ServerType.STORYTELLER_SERVICE,
    )

    @Test
    fun notConfigured_summary_promptsSetup() {
        val summary = readaloudRowSummary(
            storyteller = null,
            serverVersions = emptyMap(),
            readaloudSummaries = emptyMap(),
        )
        assertEquals("Storyteller not configured · tap to set up", summary)
    }

    @Test
    fun configured_summary_containsHost() {
        val summary = readaloudRowSummary(
            storyteller = storytellerSource(username = "alice"),
            serverVersions = emptyMap(),
            readaloudSummaries = emptyMap(),
        )
        assertTrue(
            "summary must contain the storyteller host: $summary",
            summary.contains("storyteller.example.com"),
        )
        assertTrue("summary must include the username when set: $summary", summary.contains("alice"))
    }

    @Test
    fun configured_summary_containsVersion() {
        val summary = readaloudRowSummary(
            storyteller = storytellerSource(),
            serverVersions = mapOf("sty-1" to "0.6.2"),
            readaloudSummaries = emptyMap(),
        )
        assertTrue("summary must include server version: $summary", summary.contains("v0.6.2"))
    }

    @Test
    fun configured_summary_containsUnmatchedAndMatchedCounts() {
        val summary = readaloudRowSummary(
            storyteller = storytellerSource(),
            serverVersions = emptyMap(),
            readaloudSummaries = mapOf(
                "sty-1" to ReadaloudMatchSummary(
                    unmatchedCount = 3,
                    suggestedCount = 1,
                    partiallyMatchedCount = 0,
                    matchedCount = 12,
                ),
            ),
        )
        assertTrue("summary must surface unmatched count: $summary", summary.contains("3 unmatched"))
        assertTrue("summary must surface matched count: $summary", summary.contains("12 matched"))
    }

    @Test
    fun configured_allMatched_summary_dropsUnmatchedNoise() {
        val summary = readaloudRowSummary(
            storyteller = storytellerSource(),
            serverVersions = emptyMap(),
            readaloudSummaries = mapOf(
                "sty-1" to ReadaloudMatchSummary(
                    unmatchedCount = 0,
                    suggestedCount = 0,
                    partiallyMatchedCount = 0,
                    matchedCount = 5,
                ),
            ),
        )
        assertTrue(
            "when everything is matched, subtitle should just say '5 matched': $summary",
            summary.endsWith("5 matched"),
        )
        assertTrue(
            "when everything is matched, subtitle should NOT show '0 unmatched': $summary",
            !summary.contains("unmatched"),
        )
    }
}

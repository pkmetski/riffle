package com.riffle.feature.settings

import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceUrl
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.Test

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
        assertTrue(summary.contains("storyteller.example.com"), "summary must contain the storyteller host: $summary")
        assertTrue(summary.contains("alice"), "summary must include the username when set: $summary")
    }

    @Test
    fun configured_summary_containsVersion() {
        val summary = readaloudRowSummary(
            storyteller = storytellerSource(),
            serverVersions = mapOf("sty-1" to "0.6.2"),
            readaloudSummaries = emptyMap(),
        )
        assertTrue(summary.contains("v0.6.2"), "summary must include server version: $summary")
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
        assertTrue(summary.contains("3 unmatched"), "summary must surface unmatched count: $summary")
        assertTrue(summary.contains("12 matched"), "summary must surface matched count: $summary")
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
        assertTrue(summary.endsWith("5 matched"), "when everything is matched, subtitle should just say '5 matched': $summary")
        assertTrue(!summary.contains("unmatched"), "when everything is matched, subtitle should NOT show '0 unmatched': $summary")
    }

    @Test
    fun configured_onlySuggested_summary_surfacesSuggested() {
        // Regression: pre-fix the summary silently dropped `suggestedCount` and rendered
        // "0 matched" when only pending suggestions existed — misleading, because the user has
        // work to do (accept/reject the suggestions) but the row implied nothing was there.
        val summary = readaloudRowSummary(
            storyteller = storytellerSource(),
            serverVersions = emptyMap(),
            readaloudSummaries = mapOf(
                "sty-1" to ReadaloudMatchSummary(
                    unmatchedCount = 0,
                    suggestedCount = 3,
                    partiallyMatchedCount = 0,
                    matchedCount = 0,
                ),
            ),
        )
        assertTrue(summary.contains("3 suggested"), "suggested count must surface: $summary")
        assertTrue(!summary.contains("0 matched"), "must not fall back to '0 matched' when nothing is matched: $summary")
    }

    @Test
    fun configured_onlyPartial_summary_surfacesPartial() {
        val summary = readaloudRowSummary(
            storyteller = storytellerSource(),
            serverVersions = emptyMap(),
            readaloudSummaries = mapOf(
                "sty-1" to ReadaloudMatchSummary(
                    unmatchedCount = 0,
                    suggestedCount = 0,
                    partiallyMatchedCount = 2,
                    matchedCount = 0,
                ),
            ),
        )
        assertTrue(summary.contains("2 partial"), "partial count must surface: $summary")
    }

    @Test
    fun configured_allZero_summary_saysNoReadaloudsYet() {
        val summary = readaloudRowSummary(
            storyteller = storytellerSource(),
            serverVersions = emptyMap(),
            readaloudSummaries = mapOf(
                "sty-1" to ReadaloudMatchSummary(
                    unmatchedCount = 0,
                    suggestedCount = 0,
                    partiallyMatchedCount = 0,
                    matchedCount = 0,
                ),
            ),
        )
        assertTrue(summary.endsWith("no readalouds yet"), "empty summary should collapse to 'no readalouds yet': $summary")
    }
}

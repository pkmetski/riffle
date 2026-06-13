package com.riffle.app.feature.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The facts line shown beneath the cover on the landscape two-column player. Joins the medium label,
 * duration and up to two genres; collapses to null when only the bare "Audiobook" label would show.
 */
class BuildAudiobookFactsTest {

    @Test
    fun `joins duration and genres after the medium label`() {
        assertEquals(
            "Audiobook · 10h 53m · Science Fiction & Fantasy · Adventure",
            buildAudiobookFacts(
                durationSec = 10 * 3600.0 + 53 * 60,
                genres = listOf("Science Fiction & Fantasy", "Adventure"),
            ),
        )
    }

    @Test
    fun `caps at two genres`() {
        assertEquals(
            "Audiobook · 5h · A · B",
            buildAudiobookFacts(durationSec = 5 * 3600.0, genres = listOf("A", "B", "C")),
        )
    }

    @Test
    fun `minutes-only duration omits the hours segment`() {
        assertEquals("Audiobook · 42m", buildAudiobookFacts(durationSec = 42 * 60.0, genres = emptyList()))
    }

    @Test
    fun `unknown duration and no genres collapses to null`() {
        assertNull(buildAudiobookFacts(durationSec = 0.0, genres = emptyList()))
    }

    @Test
    fun `genres alone still produce a line`() {
        assertEquals("Audiobook · Memoir", buildAudiobookFacts(durationSec = 0.0, genres = listOf("Memoir")))
    }
}

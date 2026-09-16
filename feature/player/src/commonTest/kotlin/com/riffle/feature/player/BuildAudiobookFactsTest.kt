package com.riffle.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuildAudiobookFactsTest {

    @Test
    fun joinsDistinctDurationAndGenresAfterMediumLabel() {
        assertEquals(
            "Audiobook · 10h 53m · Science Fiction & Fantasy · Adventure",
            buildAudiobookFacts(
                durationSec = 10 * 3600.0 + 53 * 60,
                genres = listOf("Science Fiction & Fantasy", "Adventure"),
            ),
        )
    }

    @Test
    fun capsAtTwoGenres() {
        assertEquals(
            "Audiobook · 5h · A · B",
            buildAudiobookFacts(durationSec = 5 * 3600.0, genres = listOf("A", "B", "C")),
        )
    }

    @Test
    fun minutesOnlyDurationOmitsHoursSegment() {
        assertEquals("Audiobook · 42m", buildAudiobookFacts(durationSec = 42 * 60.0, genres = emptyList()))
    }

    @Test
    fun durationLabelsUseLocalizedCompactTemplates() {
        val labels = CompactDurationLabelTemplates(
            minutes = "%1\$d мин",
            hours = "%1\$d ч",
            hoursMinutes = "%1\$d ч %2\$d мин",
        )

        assertEquals(
            "Аудиокнига · 10 ч 53 мин · Фантастика",
            buildAudiobookFacts(
                durationSec = 10 * 3600.0 + 53 * 60,
                genres = listOf("Фантастика"),
                audiobookLabel = "Аудиокнига",
                durationLabels = labels,
            ),
        )
        assertEquals("42 мин", formatCompactDuration(42 * 60.0, labels))
        assertEquals("5 ч", formatCompactDuration(5 * 3600.0, labels))
    }

    @Test
    fun unknownDurationAndNoGenresCollapsesToNull() {
        assertNull(buildAudiobookFacts(durationSec = 0.0, genres = emptyList()))
    }

    @Test
    fun genresAloneStillProduceALine() {
        assertEquals("Audiobook · Memoir", buildAudiobookFacts(durationSec = 0.0, genres = listOf("Memoir")))
    }
}

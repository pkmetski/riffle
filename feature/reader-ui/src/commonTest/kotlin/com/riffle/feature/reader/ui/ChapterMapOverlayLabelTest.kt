package com.riffle.feature.reader.ui

import com.riffle.core.common.TimeRemaining
import kotlin.test.Test
import kotlin.test.assertEquals

class ChapterMapOverlayLabelTest {

    private val spanishTemplates = ChapterMapProgressLabelTemplates(
        chapterCount = "Capítulo %1\$d de %2\$d",
        durationMinutes = "%1\$d min",
        durationHoursMinutes = "%1\$d h %2\$d min",
        chapterRemainingExact = "%1\$s capítulo",
        chapterRemainingEstimated = "~%1\$s capítulo",
        chapterRemainingLessThanMinute = "< 1 min capítulo",
        bookRemainingExact = "%1\$s total",
        bookRemainingEstimated = "~%1\$s total",
        bookRemainingLessThanMinute = "< 1 min total",
        readingProgressValue = "Progreso de lectura: %1\$s",
        currentChapterValue = "Capítulo actual: %1\$s",
        totalProgressValue = "Progreso total: %1\$s",
        activeRailSegmentProgress = "Segmento activo: %1\$s. Progreso %2\$d%%",
    )

    @Test
    fun `chapter count label uses localized template`() {
        assertEquals("Capítulo 2 de 7", formatChapterCount(1, 7, spanishTemplates))
        assertEquals("Capítulo 7 de 7", formatChapterCount(12, 7, spanishTemplates))
    }

    @Test
    fun `estimated time labels use localized duration and suffix templates`() {
        assertEquals("1 h 5 min", formatDuration(3_900, spanishTemplates))
        assertEquals("~1 h 5 min capítulo", formatChapterRemaining(TimeRemaining.Estimated(3_900), spanishTemplates))
        assertEquals("~2 h 5 min total", formatBookRemaining(TimeRemaining.Estimated(7_500), spanishTemplates))
        assertEquals("< 1 min capítulo", formatChapterRemaining(TimeRemaining.Estimated(59), spanishTemplates))
        assertEquals("< 1 min total", formatBookRemaining(TimeRemaining.Estimated(59), spanishTemplates))
    }

    @Test
    fun `exact time labels use localized suffix templates`() {
        assertEquals("1:02:03 capítulo", formatChapterRemaining(TimeRemaining.Exact(3_723), spanishTemplates))
        assertEquals("12:05 total", formatBookRemaining(TimeRemaining.Exact(725), spanishTemplates))
    }

    // The accessibility strings and the rail's own description go through the same expander, so a
    // regression in `formatTemplate` would silently reach VoiceOver/TalkBack rather than the
    // visible labels. `%%` is the one escape any of Riffle's reader strings uses.
    @Test
    fun accessibilityTemplatesExpandPositionalArgumentsAndPercentEscape() {
        assertEquals(
            "Progreso de lectura: Capítulo 2 de 7",
            formatTemplate(spanishTemplates.readingProgressValue, "Capítulo 2 de 7"),
        )
        assertEquals(
            "Segmento activo: Prólogo. Progreso 42%",
            formatTemplate(spanishTemplates.activeRailSegmentProgress, "Prólogo", 42),
        )
    }

    // `%.1f%%` is JVM-only; the shared replacement must round the same way Android shipped.
    @Test
    fun totalProgressPercentKeepsOneDecimalAndClamps() {
        assertEquals("0.0%", formatTotalProgressPercent(0f))
        assertEquals("37.4%", formatTotalProgressPercent(0.3744f))
        assertEquals("100.0%", formatTotalProgressPercent(0.9996f))
        assertEquals("100.0%", formatTotalProgressPercent(1.4f))
    }
}

package com.riffle.feature.reader.autoscroll

import com.riffle.core.domain.BookFormattingOverrides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaceNudgeTest {
    @Test
    fun returnsUpdatedOverrideWithNewWpm() {
        val out = nudgedPaceOverride(BookFormattingOverrides(), effectiveWpm = 250, newWpm = 260) { o, w ->
            o.copy(autoScrollWpm = w)
        }
        assertEquals(260, out?.autoScrollWpm)
    }

    @Test
    fun returnsNullWhenNudgeIsClamped() {
        assertNull(
            nudgedPaceOverride(BookFormattingOverrides(), effectiveWpm = 600, newWpm = null) { o, w ->
                o.copy(autoScrollWpm = w)
            },
        )
    }

    @Test
    fun preservesOtherOverrideFields() {
        val out = nudgedPaceOverride(BookFormattingOverrides(fontSize = 1.4f), 250, 260) { o, w ->
            o.copy(autoScrollWpm = w)
        }
        assertEquals(1.4f, out?.fontSize)
    }
}

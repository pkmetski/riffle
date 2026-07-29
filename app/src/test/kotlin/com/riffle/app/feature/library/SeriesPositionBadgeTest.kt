package com.riffle.app.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesPositionBadgeTest {

    @Test
    fun `series detail shows only the series position`() {
        assertEquals("#4", seriesPositionBadge("The Expanse #4"))
        assertEquals("#2.5", seriesPositionBadge("The Expanse #2.5"))
    }

    @Test
    fun `series detail omits badge when the item has no position`() {
        assertEquals(null, seriesPositionBadge("The Expanse"))
        assertEquals(null, seriesPositionBadge(null))
    }
}

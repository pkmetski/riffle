package com.riffle.app.feature.library

import com.riffle.feature.library.CoverGridLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesPositionBadgeTest {

    @Test
    fun `series detail shows only the series position`() {
        assertEquals("#4", CoverGridLayout.seriesPositionBadge("The Expanse #4"))
        assertEquals("#2.5", CoverGridLayout.seriesPositionBadge("The Expanse #2.5"))
    }

    @Test
    fun `series detail omits badge when the item has no position`() {
        assertEquals(null, CoverGridLayout.seriesPositionBadge("The Expanse"))
        assertEquals(null, CoverGridLayout.seriesPositionBadge(null))
    }
}

package com.riffle.app.feature.library

import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The library home tab anchors to the first non-empty section in this priority order:
 *   In Progress → Continue Series → Recently Added → Finished.
 *
 * The UI uses the returned key to detect when a higher-priority section arrives
 * late (and would otherwise be prepended above the user's view, shoving them down
 * to look "scrolled to the bottom"). On every key change while the user hasn't
 * dragged, the column is re-snapped to the top.
 */
class HomeLeadingSectionKeyTest {

    private fun item(title: String) = LibraryItem(
        "id-$title", "lib-1", title, "Author", null, 0f, false, false, EbookFormat.Epub,
    )

    @Test
    fun `returns null when all sections are empty`() {
        assertNull(homeLeadingSectionKey(emptyList(), emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun `in_progress wins over every other section`() {
        assertEquals(
            "in_progress",
            homeLeadingSectionKey(
                inProgress = listOf(item("A")),
                continueSeries = listOf(item("B")),
                recentlyAdded = listOf(item("C")),
                finished = listOf(item("D")),
            ),
        )
    }

    @Test
    fun `continue_series wins when in_progress is empty`() {
        assertEquals(
            "continue_series",
            homeLeadingSectionKey(
                inProgress = emptyList(),
                continueSeries = listOf(item("B")),
                recentlyAdded = listOf(item("C")),
                finished = listOf(item("D")),
            ),
        )
    }

    @Test
    fun `recently_added wins when in_progress and continue_series are empty`() {
        assertEquals(
            "recently_added",
            homeLeadingSectionKey(
                inProgress = emptyList(),
                continueSeries = emptyList(),
                recentlyAdded = listOf(item("C")),
                finished = listOf(item("D")),
            ),
        )
    }

    @Test
    fun `finished is the fallback`() {
        assertEquals(
            "finished",
            homeLeadingSectionKey(
                inProgress = emptyList(),
                continueSeries = emptyList(),
                recentlyAdded = emptyList(),
                finished = listOf(item("D")),
            ),
        )
    }

    /**
     * Regression for "app opens scrolled to the bottom":
     * Finished hydrates first from local cache, then In Progress arrives over the network.
     * The key must change so the home tab knows to re-anchor to the top.
     */
    @Test
    fun `key changes when a higher-priority section arrives after a lower one`() {
        val onlyFinished = homeLeadingSectionKey(
            inProgress = emptyList(),
            continueSeries = emptyList(),
            recentlyAdded = emptyList(),
            finished = listOf(item("D")),
        )
        val laterWithInProgress = homeLeadingSectionKey(
            inProgress = listOf(item("A")),
            continueSeries = emptyList(),
            recentlyAdded = emptyList(),
            finished = listOf(item("D")),
        )
        assertEquals("finished", onlyFinished)
        assertEquals("in_progress", laterWithInProgress)
    }
}

package com.riffle.app.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeriesDetailGridTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun coverShowsCompactSeriesPositionBadge() {
        rule.setContent {
            SeriesDetailGrid(
                items = listOf(item(seriesName = "The Expanse #4")),
                token = "",
                onItemSelected = {},
            )
        }

        rule.onNodeWithText("#4").assertIsDisplayed()
    }

    private fun item(seriesName: String) = LibraryItem(
        id = "book-4",
        libraryId = "library-1",
        title = "Cibola Burn",
        author = "James S. A. Corey",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        seriesName = seriesName,
    )
}

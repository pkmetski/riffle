package com.riffle.app.feature.library

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookCoverTileReadaloudTest {
    @get:Rule val rule = createComposeRule()

    private fun item() = LibraryItem(
        id = "abs-1", libraryId = "lib-1", title = "Matched", author = "A", coverUrl = null,
        readingProgress = 0f, isCached = false, isDownloaded = false, ebookFormat = EbookFormat.Epub,
    )

    @Test fun shows_readaloud_icon_when_linked() {
        rule.setContent { BookCoverTile(item = item(), token = "", onClick = {}, hasReadaloudLink = true) }
        rule.onNodeWithContentDescription("Has readaloud (synced narration)").assertIsDisplayed()
    }

    @Test fun no_icon_when_not_linked() {
        rule.setContent { BookCoverTile(item = item(), token = "", onClick = {}, hasReadaloudLink = false) }
        rule.onAllNodesWithContentDescription("Has readaloud (synced narration)").assertCountEquals(0)
    }
}

package com.riffle.app.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadaloudDownloadButtonTest {
    @get:Rule val rule = createComposeRule()

    @Test fun not_downloaded_tap_invokes_download() {
        var downloaded = false
        rule.setContent {
            ReadaloudDownloadButton(state = DownloadState.NotDownloaded, onDownload = { downloaded = true }, onRemove = {})
        }
        rule.onNodeWithContentDescription("Download readaloud").assertIsDisplayed().performClick()
        assert(downloaded)
    }

    @Test fun downloaded_tap_invokes_remove() {
        var removed = false
        rule.setContent {
            ReadaloudDownloadButton(state = DownloadState.Downloaded, onDownload = {}, onRemove = { removed = true })
        }
        rule.onNodeWithContentDescription("Remove readaloud download").assertIsDisplayed().performClick()
        assert(removed)
    }
}

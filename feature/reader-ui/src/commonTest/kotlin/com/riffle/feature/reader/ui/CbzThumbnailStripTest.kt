package com.riffle.feature.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.feature.designsystem.TestTags
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shared regression: the CBZ nav row is a thumbnail strip, not a Material Slider.
 * If someone reverts to `Slider`, the `cbz_thumb_*` semantic nodes disappear and
 * the assertions below flip red.
 */
@OptIn(ExperimentalTestApi::class)
class CbzThumbnailStripTest {

    @Test
    fun renders_strip_and_tap_routes_to_onSeek() = runComposeUiTest {
        var lastSeek = -1
        setContent {
            CbzThumbnailStrip(
                currentPage = 0,
                pageCount = 12,
                onSeek = { lastSeek = it },
                thumbnailContent = { modifier, _ -> Box(modifier = modifier.fillMaxSize()) },
            )
        }

        onNodeWithTag(TestTags.CBZ_THUMBNAIL_STRIP).assertIsDisplayed()
        onNodeWithTag(TestTags.cbzThumb(0)).assertIsDisplayed()
        onNodeWithTag(TestTags.cbzThumb(3)).performClick()

        assertEquals(3, lastSeek)
    }
}

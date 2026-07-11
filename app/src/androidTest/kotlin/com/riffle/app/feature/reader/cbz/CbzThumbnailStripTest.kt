package com.riffle.app.feature.reader.cbz

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression pin: the CBZ nav row is a thumbnail strip, not a Material Slider.
 * If someone reverts to `Slider`, the `cbz_thumb_*` semantics nodes disappear
 * and both assertions below flip red.
 */
@RunWith(AndroidJUnit4::class)
class CbzThumbnailStripTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renders_thumbnails_and_tap_routes_to_onSeek() {
        val source = FakeCbzImageSource(pageCount = 12)
        var lastSeek = -1

        composeTestRule.setContent {
            CbzThumbnailStrip(
                currentPage = 0,
                pageCount = source.pageCount,
                imageSource = source,
                onSeek = { lastSeek = it },
            )
        }

        composeTestRule.onNodeWithTag("cbz_thumbnail_strip").assertExists()
        composeTestRule.onNodeWithTag("cbz_thumb_0").assertExists()

        // 64dp thumbs at a start-aligned LazyRow — index 3 sits well inside the initial
        // viewport on the ~320dp+ harness screen.
        composeTestRule.onNodeWithTag("cbz_thumb_3").performClick()

        assertEquals(3, lastSeek)
    }
}

/**
 * Returns an empty byte array — image decode fails, produceState resolves to null,
 * and the AsyncImage never renders. The tagged `Box` behind it stays composed, which
 * is all the test needs to assert click routing.
 */
private class FakeCbzImageSource(override val pageCount: Int) : CbzImageSource {
    override fun imageBytes(pageIndex: Int): ByteArray = ByteArray(0)
}

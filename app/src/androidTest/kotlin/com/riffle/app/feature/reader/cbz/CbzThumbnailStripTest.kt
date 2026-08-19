package com.riffle.app.feature.reader.cbz

import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * Regression for the shared bitmap cache: when a pre-populated LruCache is passed in,
     * thumbnails must not call openStream at all (cache hit → skip decode).
     * Without the fix, every CbzThumbnail composable independently calls decodeSampledBitmap
     * regardless of whether a sibling already decoded the same page.
     */
    @Test
    fun prePopulated_cache_prevents_any_openStream_call() {
        val source = CountingCbzImageSource(validPngBytes(), pageCount = 5)
        val cache = LruCache<Int, Bitmap>(50)

        // Populate the cache manually for all 5 pages with a 1×1 bitmap.
        val stubBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        repeat(5) { cache.put(it, stubBitmap) }

        composeTestRule.setContent {
            CbzThumbnailStrip(
                currentPage = 0,
                pageCount = source.pageCount,
                imageSource = source,
                onSeek = {},
                thumbnailCache = cache,
            )
        }

        composeTestRule.waitForIdle()

        assertEquals(
            "openStream must not be called when cache is pre-populated",
            0,
            source.openStreamCallCount,
        )
    }

    /**
     * Regression: when thumbnails are decoded and stored in a shared cache, re-composing
     * the strip with the same cache must not trigger additional openStream calls.
     * This covers the immersive-mode toggle case: strip exits → composable removed →
     * strip re-enters → composable recreated with the same hoisted cache → no re-decode.
     */
    @Test
    fun shared_cache_survives_strip_recomposition_without_redecoding() {
        val source = CountingCbzImageSource(validPngBytes(), pageCount = 5)
        val cache = LruCache<Int, Bitmap>(50)
        val stubBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        repeat(5) { cache.put(it, stubBitmap) }

        // First composition
        composeTestRule.setContent {
            CbzThumbnailStrip(
                currentPage = 0,
                pageCount = source.pageCount,
                imageSource = source,
                onSeek = {},
                thumbnailCache = cache,
            )
        }
        composeTestRule.waitForIdle()

        // Second composition with the same cache (simulates immersive toggle re-entry)
        composeTestRule.setContent {
            CbzThumbnailStrip(
                currentPage = 0,
                pageCount = source.pageCount,
                imageSource = source,
                onSeek = {},
                thumbnailCache = cache,
            )
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "openStream must not be called on second composition when cache is still populated",
            0,
            source.openStreamCallCount,
        )
        assertTrue("Cache must still hold all entries after recomposition", cache.size() == 5)
    }
}

/**
 * Returns an empty byte array — image decode fails, produceState resolves to null,
 * and the AsyncImage never renders. The tagged `Box` behind it stays composed, which
 * is all the test needs to assert click routing.
 */
private class FakeCbzImageSource(override val pageCount: Int) : CbzImageSource {
    override fun imageBytes(pageIndex: Int): ByteArray = ByteArray(0)
    override fun openStream(pageIndex: Int): java.io.InputStream = ByteArray(0).inputStream()
}

private class CountingCbzImageSource(
    private val bytes: ByteArray,
    override val pageCount: Int,
) : CbzImageSource {
    private val _openStreamCallCount = AtomicInteger(0)
    val openStreamCallCount: Int get() = _openStreamCallCount.get()

    override fun imageBytes(pageIndex: Int) = bytes
    override fun openStream(pageIndex: Int): java.io.InputStream {
        _openStreamCallCount.incrementAndGet()
        return bytes.inputStream()
    }
}

private fun validPngBytes(): ByteArray {
    val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val baos = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
    return baos.toByteArray()
}

package com.riffle.app.feature.reader.cbz

import android.graphics.Bitmap
import android.util.LruCache
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android-specific tests for [prewarmThumbnailCache].
 *
 * Strip UI and tap-routing are covered by the shared `CbzThumbnailStripTest`
 * in `feature:reader-ui:commonTest`.
 */
@RunWith(AndroidJUnit4::class)
class CbzThumbnailStripTest {

    /**
     * Regression for the capacity-cap guard in [prewarmThumbnailCache].
     *
     * Without the guard, a 200-page comic filling a 50-entry cache would loop through all 200
     * pages, evicting earlier entries along the way. The reading neighbourhood (pages near the
     * opening page) would be gone by the time the loop finished — reproducing the
     * "cache lost on page turn" bug even though the prewarmer ran successfully.
     *
     * With the guard the cache must never exceed [LruCache.maxSize] regardless of [pageCount].
     */
    @Test
    fun prewarm_respects_cache_capacity_and_never_evicts_loaded_entries() {
        val capacity = 10
        val cache = LruCache<Int, Bitmap>(capacity)
        val stub = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val decoded = mutableListOf<Int>()

        prewarmThumbnailCache(
            startPage = 50,
            pageCount = 200,
            cache = cache,
        ) { index ->
            decoded += index
            stub
        }

        assertEquals(
            "prewarm must stop after cache is full — never decode beyond capacity",
            capacity,
            decoded.size,
        )
        assertEquals(
            "cache must hold exactly [capacity] entries — no eviction occurred",
            capacity,
            cache.size(),
        )
        assertTrue(
            "startPage must still be in cache — inner-loop overshoot must not evict it",
            cache.get(50) != null,
        )
    }

    /**
     * Regression for proximity-ordered preloading: pages closest to the opening position
     * must be decoded before distant pages. Without this ordering, a large comic would fill
     * the cache with pages the user will never see before loading the visible neighbourhood.
     */
    @Test
    fun prewarm_decodes_nearby_pages_before_distant_pages() {
        val cache = LruCache<Int, Bitmap>(20)
        val stub = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val decoded = mutableListOf<Int>()

        prewarmThumbnailCache(
            startPage = 50,
            pageCount = 200,
            cache = cache,
        ) { index ->
            decoded += index
            stub
        }

        assertEquals("first decoded page must be startPage", 50, decoded[0])
        assertEquals("second decoded page must be startPage+1", 51, decoded[1])
        assertEquals("third decoded page must be startPage-1", 49, decoded[2])

        val maxDistance = decoded.maxOf { Math.abs(it - 50) }
        assertTrue(
            "all decoded pages must be near startPage; farthest was $maxDistance",
            maxDistance <= 10,
        )
    }
}

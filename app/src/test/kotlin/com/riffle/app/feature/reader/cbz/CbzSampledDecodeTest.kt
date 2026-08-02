package com.riffle.app.feature.reader.cbz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins calculateInSampleSize logic. If this function regresses, very large BMP pages
 * (e.g. 274MB in The Complete Maus CBZ) will OOM the decoder instead of being subsampled.
 */
class CbzSampledDecodeTest {

    @Test
    fun image_smaller_than_max_needs_no_subsampling() {
        assertEquals(1, calculateInSampleSize(1080, 1920, 4096))
        assertEquals(1, calculateInSampleSize(4096, 4096, 4096))
    }

    @Test
    fun image_larger_than_max_is_subsampled_to_power_of_two() {
        // 9570x9570: 9570/1>4096, 9570/2=4785>4096, 9570/4=2392≤4096 → sampleSize=4
        assertEquals(4, calculateInSampleSize(9570, 9570, 4096))
    }

    @Test
    fun portrait_image_uses_larger_dimension_for_sample_size() {
        // Width 1000, height 9000 — height drives the sample size
        // 9000/1>4096, 9000/2=4500>4096, 9000/4=2250≤4096 → sampleSize=4
        assertEquals(4, calculateInSampleSize(1000, 9000, 4096))
    }

    @Test
    fun thumbnail_max_dimension_aggressively_subsamples_large_images() {
        // 9570px wide, maxDim=256: need sampleSize such that 9570/s≤256 → s≥37.4 → next pow2=64
        val s = calculateInSampleSize(9570, 9570, 256)
        assertTrue("expected sampleSize to bring 9570px below 256, got $s", 9570 / s <= 256)
        // Must be a power of two
        assertEquals(0, s and (s - 1))
    }

    @Test
    fun sample_size_is_always_power_of_two() {
        listOf(
            Triple(5000, 3000, 4096),
            Triple(16000, 12000, 2048),
            Triple(800, 600, 256),
        ).forEach { (w, h, max) ->
            val s = calculateInSampleSize(w, h, max)
            assertEquals("sampleSize $s for ${w}x${h} max=$max must be power of 2", 0, s and (s - 1))
        }
    }

    // Regression for the BMP OOM fix: decodeSampledBitmap must catch OutOfMemoryError on each
    // attempt and retry with a doubled inSampleSize until sampleSize > 64 (7 iterations:
    // 1,2,4,8,16,32,64), then return null — never propagating the OOM to the caller.
    //
    // Simulates a BMP image: the bounds pass (inJustDecodeBounds) OOMs too, so the retry loop
    // starts at sampleSize=1. Total openStream calls = 1 (bounds pass OOM) + 7 (retry loop) = 8.
    // openStream throws before BitmapFactory is invoked to avoid the Android JVM stub.
    @Test
    fun decode_retries_all_sample_sizes_and_returns_null_when_always_oom() {
        var callCount = 0
        val alwaysOomSource = object : CbzImageSource {
            override val pageCount = 1
            override fun imageBytes(pageIndex: Int): ByteArray = ByteArray(0)
            override fun openStream(pageIndex: Int): java.io.InputStream {
                callCount++
                throw OutOfMemoryError("simulated OOM")
            }
        }
        val result = runCatching { decodeSampledBitmap(alwaysOomSource, 0, 4096) }
        assertTrue("decodeSampledBitmap must not propagate OutOfMemoryError", result.isSuccess)
        assertEquals(null, result.getOrNull())
        // 1 bounds-pass OOM + 7 retry attempts (sampleSize 1,2,4,8,16,32,64) = 8 total
        assertEquals("bounds pass + retry loop must account for all openStream calls", 8, callCount)
    }
}

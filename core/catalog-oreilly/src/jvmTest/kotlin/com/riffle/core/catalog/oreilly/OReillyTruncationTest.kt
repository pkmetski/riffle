package com.riffle.core.catalog.oreilly

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OReillyTruncationTest {

    @Test
    fun `a 2KB sample of a 60KB chapter is detected as truncated`() {
        assertTrue(OReillyCatalog.isTruncatedSample(fetchedLen = 1976, expectedSize = 62706))
    }

    @Test
    fun `a full chapter is not flagged`() {
        assertFalse(OReillyCatalog.isTruncatedSample(fetchedLen = 62000, expectedSize = 62706))
    }

    @Test
    fun `small front matter matching its declared size is not flagged`() {
        // cover.html: ~170 bytes, declared ~170 — below the min threshold, always accepted.
        assertFalse(OReillyCatalog.isTruncatedSample(fetchedLen = 170, expectedSize = 170))
        assertFalse(OReillyCatalog.isTruncatedSample(fetchedLen = 2871, expectedSize = 3166))
    }

    @Test
    fun `unknown declared size (0) is never flagged`() {
        assertFalse(OReillyCatalog.isTruncatedSample(fetchedLen = 1976, expectedSize = 0))
    }

    @Test
    fun `a full multibyte chapter is measured in UTF-8 bytes, not UTF-16 chars`() {
        // 21000 CJK chars encode to ~63000 UTF-8 bytes (3 bytes each). Measured in bytes it clears the
        // declared size; measured as String.length (chars) it would fall under expectedSize/2 and be
        // falsely flagged as a truncated DRM sample. isTruncatedBody must use the byte count.
        val fullChapter = "第".repeat(21000)
        assertFalse(OReillyCatalog.isTruncatedBody(fullChapter, expectedSize = 63000))
        // Guard the intent: the raw char count really would have tripped the check.
        assertTrue(OReillyCatalog.isTruncatedSample(fullChapter.length.toLong(), expectedSize = 63000))
    }
}

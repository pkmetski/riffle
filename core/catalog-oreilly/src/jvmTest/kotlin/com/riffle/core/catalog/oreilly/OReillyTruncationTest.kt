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
}

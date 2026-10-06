package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden tests for [PdfLocatorCodec] — encode/decode round-trips must be stable across platforms.
 *
 * These tests pin both the wire format (so Android's `Locator.fromJSON` can parse iOS-written
 * locators) and the old-iOS migration path (so existing saved positions continue to work without a
 * DB migration).
 */
class PdfLocatorCodecTest {

    // ── encode ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun encodeFirstPageUses1BasedPosition() {
        val json = PdfLocatorCodec.encode(page0Based = 0, pageCount = 10)
        assertTrue(json.contains(""""position":1"""), "first page must encode as position=1, got: $json")
        assertTrue(json.contains(""""href":"/page-1""""), "href must be /page-1 for first page, got: $json")
    }

    @Test
    fun encodeLastPageUses1BasedPosition() {
        val json = PdfLocatorCodec.encode(page0Based = 9, pageCount = 10)
        assertTrue(json.contains(""""position":10"""), "last page must encode as position=10, got: $json")
        assertTrue(json.contains(""""href":"/page-10""""), "href must be /page-10 for last page, got: $json")
    }

    @Test
    fun encodeProgressionIsZeroBasedFraction() {
        val json = PdfLocatorCodec.encode(page0Based = 5, pageCount = 10)
        assertTrue(json.contains(""""progression":0.5"""), "progression must be 0.5 for page 5 of 10, got: $json")
    }

    @Test
    fun encodeFirstPageProgressionIsZero() {
        val json = PdfLocatorCodec.encode(page0Based = 0, pageCount = 10)
        assertTrue(json.contains(""""progression":0.0"""), "first page progression must be 0.0, got: $json")
    }

    @Test
    fun encodeZeroPageCountProgressionIsZero() {
        val json = PdfLocatorCodec.encode(page0Based = 3, pageCount = 0)
        assertTrue(json.contains(""""progression":0.0"""), "progression must be 0.0 when pageCount=0, got: $json")
    }

    // ── decode — new 1-based format ───────────────────────────────────────────────────────────────

    @Test
    fun decodeRoundTripsFirstPage() {
        val json = PdfLocatorCodec.encode(page0Based = 0, pageCount = 10)
        assertEquals(0, PdfLocatorCodec.decode0Based(json), "first page must round-trip to 0")
    }

    @Test
    fun decodeRoundTripsMiddlePage() {
        val json = PdfLocatorCodec.encode(page0Based = 5, pageCount = 10)
        assertEquals(5, PdfLocatorCodec.decode0Based(json), "middle page must round-trip correctly")
    }

    @Test
    fun decodeRoundTripsLastPage() {
        val json = PdfLocatorCodec.encode(page0Based = 9, pageCount = 10)
        assertEquals(9, PdfLocatorCodec.decode0Based(json), "last page must round-trip to 9")
    }

    // ── decode — old iOS 0-based format migration ─────────────────────────────────────────────────

    @Test
    fun decodeOldIosFirstPageReturnsZero() {
        // Old iOS format: href="/page-0", position=0 — both 0-based.
        val oldLocator = """{"href":"/page-0","type":"application/pdf","locations":{"position":0,"progression":0.0}}"""
        assertEquals(0, PdfLocatorCodec.decode0Based(oldLocator), "old iOS first page must map to page 0")
    }

    @Test
    fun decodeOldIosMiddlePagePreservesValue() {
        // Old iOS format: href="/page-5", position=5 (0-based, means 6th page).
        val oldLocator = """{"href":"/page-5","type":"application/pdf","locations":{"position":5,"progression":0.5}}"""
        assertEquals(5, PdfLocatorCodec.decode0Based(oldLocator), "old iOS page 5 must decode back to 5 (0-based)")
    }

    // ── decode — Android / Readium format ────────────────────────────────────────────────────────

    @Test
    fun decodeReadiumFirstPageReturnsZero() {
        // Android uses Readium Locator.toJSON(), which has 1-based position but a different href.
        val readiumLocator = """{"href":"/OEBPS/document.pdf","type":"application/pdf","locations":{"position":1,"progression":0.0}}"""
        assertEquals(0, PdfLocatorCodec.decode0Based(readiumLocator), "Readium position=1 must decode to page 0")
    }

    @Test
    fun decodeReadiumPageFiveReturnsIndex4() {
        val readiumLocator = """{"href":"/OEBPS/document.pdf","type":"application/pdf","locations":{"position":5,"progression":0.4}}"""
        assertEquals(4, PdfLocatorCodec.decode0Based(readiumLocator), "Readium position=5 must decode to page index 4")
    }

    // ── decode — edge cases ───────────────────────────────────────────────────────────────────────

    @Test
    fun decodeReturnsNullForNonPdfLocator() {
        assertNull(PdfLocatorCodec.decode0Based("""{"href":"/chapter1.xhtml","locations":{}}"""))
    }

    @Test
    fun decodeReturnsNullForEmptyString() {
        assertNull(PdfLocatorCodec.decode0Based(""))
    }
}

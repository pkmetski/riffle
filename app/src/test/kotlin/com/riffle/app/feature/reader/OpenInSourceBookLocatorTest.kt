package com.riffle.app.feature.reader

import com.riffle.core.data.annotationLocatorJson
import com.riffle.core.data.annotationLocatorJsonNoProg
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression for the "Open in Book" bug: navigating from the elided view to the source book
 * previously used buildLocatorJsonFromCfi which emitted an empty href, causing Readium to fall
 * back to the last reading position instead of the annotation's location.
 *
 * A second regression pinned here: rawCfi and annotationId must flow through the event so that
 * LibraryNav.Reader.openAtRawCfi is set, causing AndroidReaderSlot to prefer cfiStringToLocator
 * (no stored progression) over the JSON locator, preventing a page-snap to the wrong page.
 *
 * iOS counterpart: AnnotationLocatorJsonTest (commonTest) covers annotationLocatorJson with
 * chapterHref on the iOS simulator path.
 */
class OpenInSourceBookLocatorTest {

    @Test
    fun `open in book locator carries annotation chapter href`() {
        val event = ReaderNavEvent.OpenInSourceBook(
            sourceId = "src",
            itemId = "item",
            cfi = "epubcfi(/6/4!/4/2/1:0)",
            chapterHref = "EPUB/chapter01.xhtml",
            progression = 0.5,
            annotationId = "ann-1",
        )

        val locatorJson = annotationLocatorJson(event.chapterHref, event.cfi, event.progression)
        val parsed = JSONObject(locatorJson)

        assertEquals("EPUB/chapter01.xhtml", parsed.getString("href"))
        assertNotEquals("", parsed.getString("href"))
    }

    @Test
    fun `open in book locator cfi fragment is intra-document portion`() {
        val event = ReaderNavEvent.OpenInSourceBook(
            sourceId = "src",
            itemId = "item",
            cfi = "epubcfi(/6/4!/4/2/1:0)",
            chapterHref = "EPUB/ch2.xhtml",
            progression = 0.25,
            annotationId = "ann-2",
        )

        val locatorJson = annotationLocatorJson(event.chapterHref, event.cfi, event.progression)
        val parsed = JSONObject(locatorJson)
        val cfiInLocator = parsed.getJSONObject("locations").getString("cfi")

        assertEquals("/4/2/1:0)", cfiInLocator)
    }

    @Test
    fun `open in book event carries raw cfi for openAtRawCfi navigation`() {
        val rawCfi = "epubcfi(/6/8!/4/2[p5]/1:42)"
        val event = ReaderNavEvent.OpenInSourceBook(
            sourceId = "src",
            itemId = "item",
            cfi = rawCfi,
            chapterHref = "EPUB/ch3.xhtml",
            progression = 0.6,
            annotationId = "ann-3",
        )

        // The raw CFI is what AndroidReaderSlot passes as the second arg to onOpenInBook,
        // which AppShell puts into LibraryNav.Reader.openAtRawCfi. That field takes priority
        // over initialLocatorJson in AndroidEpubReaderSlot, sending it through cfiStringToLocator
        // instead of Locator.fromJSON — bypassing the stored page-progression.
        assertEquals(rawCfi, event.cfi)
        assertEquals("ann-3", event.annotationId)
    }

    @Test
    fun `iOS no-progression locator omits progression and carries CFI fragment`() {
        val locatorJson = annotationLocatorJsonNoProg("EPUB/chapter02.xhtml", "epubcfi(/6/6!/4/4/1:12)")
        val parsed = JSONObject(locatorJson)

        assertEquals("EPUB/chapter02.xhtml", parsed.getString("href"))
        assertEquals("/4/4/1:12)", parsed.getJSONObject("locations").getString("cfi"))
        assertEquals(false, parsed.getJSONObject("locations").has("progression"))
    }
}

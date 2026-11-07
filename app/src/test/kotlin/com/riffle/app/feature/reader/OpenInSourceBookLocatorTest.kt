package com.riffle.app.feature.reader

import com.riffle.core.data.annotationLocatorJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression for the "Open in Book" bug: navigating from the elided view to the source book
 * previously used buildLocatorJsonFromCfi which emitted an empty href, causing Readium to fall
 * back to the last reading position instead of the annotation's location.
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
}

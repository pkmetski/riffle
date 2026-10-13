package com.riffle.feature.reader.highlights

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnnotationTapUrlTest {

    @Test
    fun `parse returns the id round-tripped from build`() {
        val id = "abc-123"
        assertEquals(id, parseAnnotationTapUrl(buildAnnotationTapUrl(id)))
    }

    @Test
    fun `parse decodes percent-encoded ids with spaces and hashes`() {
        val id = "id with space & #hash?"
        assertEquals(id, parseAnnotationTapUrl(buildAnnotationTapUrl(id)))
    }

    @Test
    fun `parse decodes multi-byte UTF-8 sequences correctly`() {
        // Regression: single-byte toChar() decoded %C3%A9 as Ã©; multi-byte assembly must give é.
        val id = "note-é-résumé"
        assertEquals(id, parseAnnotationTapUrl(buildAnnotationTapUrl(id)))
    }

    @Test
    fun `parse returns null for http URLs`() {
        assertNull(parseAnnotationTapUrl("https://example.com/foo"))
        assertNull(parseAnnotationTapUrl("http://readium_package/OEBPS/ch1.xhtml"))
    }

    @Test
    fun `parse returns null for the wrong scheme prefix`() {
        assertNull(parseAnnotationTapUrl("riffle://other-thing/abc"))
        assertNull(parseAnnotationTapUrl("riffle-annotation-tap:abc"))
    }

    @Test
    fun `parse returns null when the id is empty`() {
        assertNull(parseAnnotationTapUrl("riffle://annotation-tap/"))
    }

    @Test
    fun `parse ignores query fragments so future rect params are safe`() {
        assertEquals("abc", parseAnnotationTapUrl("riffle://annotation-tap/abc?l=1&t=2&r=3&b=4"))
    }

    @Test
    fun `parts parses rect query params for popup positioning`() {
        val parts = parseAnnotationTapUrlParts("riffle://annotation-tap/abc?l=12&t=34&r=56&b=78")
        assertEquals("abc", parts?.annotationId)
        assertEquals(12f, parts?.cssLeft)
        assertEquals(34f, parts?.cssTop)
        assertEquals(56f, parts?.cssRight)
        assertEquals(78f, parts?.cssBottom)
        assertEquals(true, parts?.hasRect())
    }

    @Test
    fun `parts hasRect is false when no query is present`() {
        val parts = parseAnnotationTapUrlParts("riffle://annotation-tap/abc")
        assertEquals("abc", parts?.annotationId)
        assertEquals(false, parts?.hasRect())
    }
}

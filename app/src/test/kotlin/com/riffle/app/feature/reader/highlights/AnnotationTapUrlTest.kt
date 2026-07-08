package com.riffle.app.feature.reader.highlights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression pins for the `riffle-annotation-tap:` scheme handed to
 * [com.riffle.app.feature.reader.ChapterWebView] (continuous) and
 * [com.riffle.app.feature.reader.EpubReaderScreen]'s `onExternalLinkActivated`
 * (paginated/vertical). The synthesised HTML in
 * [HighlightsPublicationFactory] embeds a `location.href='<buildAnnotationTapUrl(id)>'` on the
 * accent-bar tap span; if the parser drifts from the builder (or from either interceptor's
 * `startsWith` check), the highlight menu simply never opens. Both halves are pinned by the
 * round-trip case below plus explicit rejection of unrelated URL shapes.
 */
class AnnotationTapUrlTest {

    @Test
    fun `parse returns the id round-tripped from build`() {
        val id = "abc-123"
        assertEquals(id, parseAnnotationTapUrl(buildAnnotationTapUrl(id)))
    }

    @Test
    fun `parse decodes percent-encoded ids with spaces and hashes`() {
        val id = "id with space & #hash?"
        val url = buildAnnotationTapUrl(id)
        assertEquals(id, parseAnnotationTapUrl(url))
    }

    @Test
    fun `parse returns null for http URLs`() {
        assertNull(parseAnnotationTapUrl("https://example.com/foo"))
        assertNull(parseAnnotationTapUrl("http://readium_package/OEBPS/ch1.xhtml"))
    }

    @Test
    fun `parse returns null for the wrong scheme prefix`() {
        // A missed `startsWith` check that swallowed unrelated riffle URLs would be caught here.
        assertNull(parseAnnotationTapUrl("riffle://other-thing/abc"))
        assertNull(parseAnnotationTapUrl("riffle-annotation-tap:abc"))
    }

    @Test
    fun `parse returns null when the id is empty`() {
        assertNull(parseAnnotationTapUrl("riffle://annotation-tap/"))
    }

    @Test
    fun `parse ignores query fragments so future rect params are safe`() {
        // Reserved for future expansion (e.g. bounding rect appended as ?l=&t=&r=&b=). Today the
        // interceptors pass through with a zero rect, and the parser must still return the id.
        assertEquals("abc", parseAnnotationTapUrl("riffle://annotation-tap/abc?l=1&t=2&r=3&b=4"))
    }

    // Rect query params are what makes [HighlightActionsPopup] anchor next to the tapped accent
    // bar; without them the popup lands at (0, 0) because HighlightPopupPositionProvider derives
    // its position from anchorRect. Regressing either the emitter or this parser would flip the
    // popup back to the top-left of the screen.
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

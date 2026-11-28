package com.riffle.app.feature.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the whitespace-normalization contract for [buildAnnotationRenderLocator].
 *
 * Root cause: annotations created in continuous mode capture text via window.getSelection()
 * which includes literal \n between paragraphs. Readium's text-search engine collapses
 * whitespace during matching, so the stored \n causes a mismatch and decorations are silently
 * dropped. The fix normalizes before/after to single spaces so Readium's search can match.
 *
 * CFI is intentionally NOT included in locations.fragments: the CFI is built by ksoup from
 * the raw XHTML, but Readium serves a modified DOM (injected scripts/CSS) with different element
 * indices. Using the ksoup CFI causes Readium's decoration positioning to fail silently; omitting
 * it lets Readium fall back to its text-search path, which is robust once whitespace is normalized.
 *
 * The [locatorFromJson] seam lets tests inspect the raw JSON without hitting android.net.Uri.
 */
class BuildAnnotationRenderLocatorTest {

    private val testCfi = "epubcfi(/6/4!/4/2/1:0,/1:5)"
    private val testHref = "OEBPS/chapter001.xhtml"

    private var capturedJson: JSONObject? = null
    private val capturingFactory: (JSONObject) -> Nothing? = { json ->
        capturedJson = json
        null
    }

    @Test
    fun `buildAnnotationRenderLocator normalizes newlines in before and after to single spaces`() {
        buildAnnotationRenderLocator(
            href = testHref,
            cfi = testCfi,
            progression = 0.3,
            before = "end of paragraph.\nStart of next paragraph. Some ",
            highlight = "selected word",
            after = "rest of text.\n\nAnother paragraph.",
            locatorFromJson = capturingFactory,
        )
        val json = capturedJson
        assertNotNull("locatorFromJson must be invoked", json)
        val text = json!!.optJSONObject("text")
        assertNotNull(text)
        assertEquals(
            "newlines in before must be collapsed to spaces so Readium text-search matches",
            "end of paragraph. Start of next paragraph. Some ",
            text!!.optString("before"),
        )
        assertEquals(
            "newlines in after must be collapsed to spaces so Readium text-search matches",
            "rest of text. Another paragraph.",
            text.optString("after"),
        )
    }

    @Test
    fun `buildAnnotationRenderLocator normalizes newlines in highlight to single spaces`() {
        // A highlight created in continuous mode via window.getSelection() captures literal \n
        // when the selection spans a paragraph boundary. Readium's TextQuoteSelector concatenates
        // adjacent block nodes without any separator, so a \n in the highlight causes the matched
        // range to extend past the intended end of the highlighted text.
        buildAnnotationRenderLocator(
            href = testHref,
            cfi = testCfi,
            progression = 0.3,
            before = "text before ",
            highlight = "first paragraph.\nSecond paragraph starts here",
            after = " and continues.",
            locatorFromJson = capturingFactory,
        )
        val json = capturedJson
        assertNotNull("locatorFromJson must be invoked", json)
        val text = json!!.optJSONObject("text")
        assertNotNull(text)
        assertEquals(
            "newlines in highlight must be collapsed to spaces so Readium text-search matches the right range",
            "first paragraph. Second paragraph starts here",
            text!!.optString("highlight"),
        )
    }

    @Test
    fun `buildAnnotationRenderLocator does not include CFI in locations fragments`() {
        buildAnnotationRenderLocator(
            href = testHref,
            cfi = testCfi,
            progression = 0.3,
            before = "text before",
            highlight = "selected word",
            after = "text after",
            locatorFromJson = capturingFactory,
        )
        val json = capturedJson
        assertNotNull("locatorFromJson must be invoked", json)
        val fragments = json!!.optJSONObject("locations")?.optJSONArray("fragments")
        assertNull(
            "fragments must be absent so Readium uses text-search rather than a ksoup-built CFI that mismatches the served DOM",
            fragments,
        )
    }

    @Test
    fun `buildAnnotationRenderLocator preserves href and text fields`() {
        buildAnnotationRenderLocator(
            href = testHref,
            cfi = testCfi,
            progression = 0.5,
            before = "before text",
            highlight = "highlight text",
            after = "after text",
            locatorFromJson = capturingFactory,
        )
        val json = capturedJson
        assertNotNull(json)
        assertEquals(testHref, json!!.optString("href"))
        val text = json.optJSONObject("text")
        assertNotNull(text)
        assertEquals("before text", text!!.optString("before"))
        assertEquals("highlight text", text.optString("highlight"))
        assertEquals("after text", text.optString("after"))
    }

    @Test
    fun `buildAnnotationRenderLocator propagates progression`() {
        buildAnnotationRenderLocator(
            href = testHref,
            cfi = testCfi,
            progression = 0.75,
            before = "",
            highlight = "word",
            after = "",
            locatorFromJson = capturingFactory,
        )
        val json = capturedJson
        assertNotNull(json)
        val progression = json!!.optJSONObject("locations")?.optDouble("progression")
        assertEquals(0.75, progression!!, 0.001)
    }

    // ---- annotationSegmentContexts: before/after route to first/last NON-BLANK segment ----------
    //
    // splitSnippetForFiguresAt yields a blank boundary segment when a figure sits at the start
    // (["", caption]) or end (["text", ""]) of a highlight. Keying textBefore/textAfter on raw
    // index 0 / lastIndex drops the context onto a skipped blank segment, degrading Readium's
    // TextQuoteSelector disambiguation. These pin the non-blank routing on both ends.

    @Test
    fun `annotationSegmentContexts routes textAfter to last non-blank when figure is at end`() {
        // Figure at the end → trailing blank segment. textAfter must still attach to "text".
        val contexts = annotationSegmentContexts(listOf("text", ""), "BEFORE", "AFTER")
        assertEquals("only the non-blank segment is mapped", setOf(0), contexts.keys)
        assertEquals("BEFORE" to "AFTER", contexts[0])
    }

    @Test
    fun `annotationSegmentContexts routes textBefore to first non-blank when figure is at start`() {
        // Figure at the start → leading blank segment. textBefore must attach to "caption".
        val contexts = annotationSegmentContexts(listOf("", "caption"), "BEFORE", "AFTER")
        assertEquals(setOf(1), contexts.keys)
        assertEquals("BEFORE" to "AFTER", contexts[1])
    }

    @Test
    fun `annotationSegmentContexts gives middle segments empty context and ends their own`() {
        val contexts = annotationSegmentContexts(listOf("a", "b", "c"), "BEFORE", "AFTER")
        assertEquals("BEFORE" to "", contexts[0])
        assertEquals("" to "", contexts[1])
        assertEquals("" to "AFTER", contexts[2])
    }

    @Test
    fun `annotationSegmentContexts single segment carries both before and after`() {
        val contexts = annotationSegmentContexts(listOf("only"), "BEFORE", "AFTER")
        assertEquals("BEFORE" to "AFTER", contexts[0])
    }
}

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
    fun `buildAnnotationRenderLocator normalizes newlines in before to single spaces`() {
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
}

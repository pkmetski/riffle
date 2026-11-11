package com.riffle.app.feature.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pins the CFI-in-fragments contract for [buildAnnotationRenderLocator].
 *
 * Root cause: annotations created in continuous mode use JavaScript's
 * window.getSelection().toString() which may include invisible characters (soft hyphens, ZWSPs)
 * absent from the XHTML source. Readium's decoration text search then fails to match the snippet,
 * so decorations silently do not appear when switching from continuous to paginated mode.
 * Including the CFI in locations.fragments lets Readium use CFI-based positioning instead.
 *
 * The [locatorFromJson] seam is used so tests can inspect the raw JSON without hitting
 * android.net.Uri (unavailable in the JVM test stub jar).
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
    fun `buildAnnotationRenderLocator includes CFI in locations fragments`() {
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
        assertNotNull("locations.fragments must be present so Readium uses CFI-based positioning", fragments)
        assertEquals("CFI must be the sole fragment entry", testCfi, fragments!!.getString(0))
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

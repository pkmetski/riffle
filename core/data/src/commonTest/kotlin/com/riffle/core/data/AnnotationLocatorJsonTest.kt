package com.riffle.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnnotationLocatorJsonTest {

    @Test
    fun `extracts intra-doc cfi fragment after exclamation mark`() {
        val json = annotationLocatorJson(
            chapterHref = "EPUB/chapter1.xhtml",
            cfi = "epubcfi(/6/4[chap01]!/4/2/16)",
            progression = 0.25,
        )
        val root = Json.parseToJsonElement(json).jsonObject
        val cfiFragment = root["locations"]!!.jsonObject["cfi"]!!.jsonPrimitive.content
        assertEquals("/4/2/16)", cfiFragment)
    }

    @Test
    fun `href is preserved as-is from chapterHref`() {
        val json = annotationLocatorJson(
            chapterHref = "EPUB/Text/part2.xhtml",
            cfi = "epubcfi(/6/8!/2/1:0)",
            progression = 0.5,
        )
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals("EPUB/Text/part2.xhtml", root["href"]!!.jsonPrimitive.content)
    }

    @Test
    fun `type is application xhtml+xml`() {
        val json = annotationLocatorJson("ch.xhtml", "epubcfi(/6/2!/1:0)", 0.0)
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals("application/xhtml+xml", root["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `progression is included in locations`() {
        val json = annotationLocatorJson("ch.xhtml", "epubcfi(/6/2!/1:0)", 0.75)
        val root = Json.parseToJsonElement(json).jsonObject
        val prog = root["locations"]!!.jsonObject["progression"]!!.jsonPrimitive.content.toDouble()
        assertEquals(0.75, prog, 0.001)
    }

    @Test
    fun `no exclamation mark in cfi returns empty fragment`() {
        val json = annotationLocatorJson("ch.xhtml", "epubcfi(/6/2)", 0.0)
        val root = Json.parseToJsonElement(json).jsonObject
        val cfiFragment = root["locations"]!!.jsonObject["cfi"]!!.jsonPrimitive.content
        assertEquals("", cfiFragment)
    }

    // ---- annotationDecorationLocatorJson (iOS navigation path for figure annotations) ----------
    //
    // iOS calls annotationDecorationLocatorJson via annotationDecorationLocator() when the user
    // navigates to an annotation from the in-reader annotations panel. For TYPE_IMAGE annotations
    // the caption (textSnippet) is the best available text anchor; Readium Swift navigates to
    // that caption text so the reader lands near the figure. These tests pin that behaviour so a
    // caption-removal or field rename doesn't silently break iOS figure navigation.

    @Test
    fun `annotationDecorationLocatorJson for figure with caption uses caption as text highlight anchor`() {
        val json = annotationDecorationLocatorJson(
            chapterHref = "EPUB/chapter2.xhtml",
            cfi = "epubcfi(/6/6!/4/8)",
            progression = 0.42,
            textSnippet = "Figure 3.1 — Distribution of responses",
            textBefore = "",
            textAfter = "The bars in Figure 3.1",
            fragmentAnchor = null,
        )
        val root = Json.parseToJsonElement(json).jsonObject
        val textObj = root["text"]?.jsonObject
        assertEquals(
            "Figure 3.1 — Distribution of responses",
            textObj?.get("highlight")?.jsonPrimitive?.content,
            "iOS navigation for a captioned figure must use the caption text as text.highlight " +
                "so Readium Swift can resolve the exact DOM range and land at the caption " +
                "(the figure is visible just above it)",
        )
    }

    @Test
    fun `annotationDecorationLocatorJson normalizes newlines in textSnippet textBefore and textAfter`() {
        // Annotations created in continuous mode capture literal \n via window.getSelection() when
        // the selection spans a paragraph boundary. Readium's TextQuoteSelector concatenates
        // adjacent block nodes without any separator, so a \n in highlight extends the matched
        // range past the intended endpoint and degrades before/after disambiguation scores.
        val json = annotationDecorationLocatorJson(
            chapterHref = "EPUB/chapter3.xhtml",
            cfi = "epubcfi(/6/8!/4/2)",
            progression = 0.6,
            textSnippet = "first paragraph.\nSecond paragraph starts here",
            textBefore = "end of earlier paragraph.\nBeginning of context ",
            textAfter = " rest of context.\n\nAnother paragraph.",
            fragmentAnchor = null,
        )
        val root = Json.parseToJsonElement(json).jsonObject
        val textObj = root["text"]?.jsonObject
        assertEquals(
            "first paragraph. Second paragraph starts here",
            textObj?.get("highlight")?.jsonPrimitive?.content,
            "newlines in textSnippet must be collapsed to spaces",
        )
        assertEquals(
            "end of earlier paragraph. Beginning of context ",
            textObj?.get("before")?.jsonPrimitive?.content,
            "newlines in textBefore must be collapsed to spaces",
        )
        assertEquals(
            " rest of context. Another paragraph.",
            textObj?.get("after")?.jsonPrimitive?.content,
            "newlines in textAfter must be collapsed to spaces",
        )
    }

    @Test
    fun `annotationDecorationLocatorJson for figure without caption omits text block`() {
        val json = annotationDecorationLocatorJson(
            chapterHref = "EPUB/chapter2.xhtml",
            cfi = "epubcfi(/6/6!/4/8)",
            progression = 0.55,
            textSnippet = "",
            textBefore = "",
            textAfter = "",
            fragmentAnchor = null,
        )
        val root = Json.parseToJsonElement(json).jsonObject
        assertNull(
            root["text"],
            "iOS navigation for a figure with no caption must omit the text block; Readium " +
                "Swift falls back to CFI/progression which is the best available anchor for " +
                "an uncaptioned figure",
        )
        val progression = root["locations"]!!.jsonObject["progression"]!!.jsonPrimitive.content.toDouble()
        assertEquals(0.55, progression, 0.001)
    }
}

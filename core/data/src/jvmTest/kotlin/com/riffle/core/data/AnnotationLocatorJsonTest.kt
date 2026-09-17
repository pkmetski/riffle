package com.riffle.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

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
}

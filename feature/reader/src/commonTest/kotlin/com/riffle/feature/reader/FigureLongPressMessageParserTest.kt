package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FigureLongPressMessageParserTest {

    @Test
    fun parsesImgPayload() {
        val json = """{"kind":"img","caption":"A sunset","href":"images/sunset.jpg","rectX":10,"rectY":20,"rectW":300,"rectH":200}"""
        val result = FigureLongPressMessageParser.parse(json)
        assertEquals("img", result.kind)
        assertEquals("A sunset", result.caption)
        assertEquals("images/sunset.jpg", result.href)
        assertNull(result.svg)
        assertEquals(10, result.rectX)
        assertEquals(20, result.rectY)
        assertEquals(300, result.rectW)
        assertEquals(200, result.rectH)
    }

    @Test
    fun parsesSvgPayload() {
        val json = """{"kind":"svg","caption":"","svg":"<svg>...</svg>","href":null}"""
        val result = FigureLongPressMessageParser.parse(json)
        assertEquals("svg", result.kind)
        assertEquals("", result.caption)
        assertNotNull(result.svg)
        assertNull(result.href)
    }

    @Test
    fun jsonNullFieldBecomesKotlinNull() {
        val json = """{"kind":"img","caption":"fig","href":null,"svg":null,"elementId":null,"imageBytes":null}"""
        val result = FigureLongPressMessageParser.parse(json)
        assertNull(result.href)
        assertNull(result.svg)
        assertNull(result.elementId)
        assertNull(result.imageBytes)
    }

    @Test
    fun emptyStringFieldBecomesNull() {
        val json = """{"kind":"img","caption":"fig","href":"","svg":"","elementId":"","imageBytes":""}"""
        val result = FigureLongPressMessageParser.parse(json)
        assertNull(result.href)
        assertNull(result.svg)
        assertNull(result.elementId)
        assertNull(result.imageBytes)
    }

    @Test
    fun defaultsRectFieldsToZero() {
        val json = """{"kind":"img","caption":""}"""
        val result = FigureLongPressMessageParser.parse(json)
        assertEquals(0, result.rectX)
        assertEquals(0, result.rectY)
        assertEquals(0, result.rectW)
        assertEquals(0, result.rectH)
    }

    @Test
    fun parsesCaptionRange() {
        val json = """{"kind":"img","caption":"Figure 1. Caption text","captionRange":{"text":"Caption text","textBefore":"before ","textAfter":" after"}}"""
        val result = FigureLongPressMessageParser.parse(json)
        val cr = result.captionRange
        assertNotNull(cr)
        assertEquals("Caption text", cr.text)
        assertEquals("before ", cr.textBefore)
        assertEquals(" after", cr.textAfter)
    }

    @Test
    fun blankCaptionRangeTextProducesNullCaptionRange() {
        val json = """{"kind":"img","caption":"","captionRange":{"text":"  ","textBefore":"","textAfter":""}}"""
        val result = FigureLongPressMessageParser.parse(json)
        assertNull(result.captionRange)
    }

    @Test
    fun missingKindThrows() {
        assertFailsWith<Exception> {
            FigureLongPressMessageParser.parse("""{"caption":""}""")
        }
    }

    @Test
    fun ignoresUnknownFields() {
        val json = """{"kind":"img","caption":"x","unknownField":42,"another":true}"""
        val result = FigureLongPressMessageParser.parse(json)
        assertEquals("img", result.kind)
    }
}

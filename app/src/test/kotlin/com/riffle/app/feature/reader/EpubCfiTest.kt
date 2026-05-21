package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubCfiTest {

    private val readingOrder = listOf(
        "OEBPS/chapter01.xhtml",
        "OEBPS/chapter02.xhtml",
        "OEBPS/chapter03.xhtml",
    )

    @Test
    fun `first spine item produces epubcfi with step 2`() {
        assertEquals("epubcfi(/6/2!)", buildEpubCfi(readingOrder, "OEBPS/chapter01.xhtml"))
    }

    @Test
    fun `second spine item produces epubcfi with step 4`() {
        assertEquals("epubcfi(/6/4!)", buildEpubCfi(readingOrder, "OEBPS/chapter02.xhtml"))
    }

    @Test
    fun `third spine item produces epubcfi with step 6`() {
        assertEquals("epubcfi(/6/6!)", buildEpubCfi(readingOrder, "OEBPS/chapter03.xhtml"))
    }

    @Test
    fun `href not in reading order returns empty string`() {
        assertEquals("", buildEpubCfi(readingOrder, "OEBPS/unknown.xhtml"))
    }

    @Test
    fun `empty reading order returns empty string`() {
        assertEquals("", buildEpubCfi(emptyList(), "OEBPS/chapter01.xhtml"))
    }
}

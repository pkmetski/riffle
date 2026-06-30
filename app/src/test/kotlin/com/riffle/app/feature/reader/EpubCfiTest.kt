package com.riffle.app.feature.reader

import com.riffle.core.domain.epubCfiToSpineIndex
import com.riffle.core.domain.normalizeEpubHref
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
        assertEquals("epubcfi(/6/2!/4/2)", buildEpubCfi(readingOrder, "OEBPS/chapter01.xhtml"))
    }

    @Test
    fun `second spine item produces epubcfi with step 4`() {
        assertEquals("epubcfi(/6/4!/4/2)", buildEpubCfi(readingOrder, "OEBPS/chapter02.xhtml"))
    }

    @Test
    fun `third spine item produces epubcfi with step 6`() {
        assertEquals("epubcfi(/6/6!/4/2)", buildEpubCfi(readingOrder, "OEBPS/chapter03.xhtml"))
    }

    @Test
    fun `href not in reading order returns empty string`() {
        assertEquals("", buildEpubCfi(readingOrder, "OEBPS/unknown.xhtml"))
    }

    @Test
    fun `empty reading order returns empty string`() {
        assertEquals("", buildEpubCfi(emptyList(), "OEBPS/chapter01.xhtml"))
    }

    // normalizeEpubHref tests

    @Test
    fun `normalize strips zip bang prefix from file URL`() {
        val url = "file:///data/user/0/com.example/cache/book.epub!/OEBPS/chapter1.xhtml"
        assertEquals("OEBPS/chapter1.xhtml", normalizeEpubHref(url))
    }

    @Test
    fun `normalize extracts path from http localhost URL`() {
        val url = "http://127.0.0.1:8080/OEBPS/chapter1.xhtml"
        assertEquals("OEBPS/chapter1.xhtml", normalizeEpubHref(url))
    }

    @Test
    fun `normalize returns relative path unchanged`() {
        assertEquals("OEBPS/chapter1.xhtml", normalizeEpubHref("OEBPS/chapter1.xhtml"))
    }

    @Test
    fun `normalize trims leading slash from zip entry`() {
        val url = "file:///data/book.epub!/OEBPS/chapter1.xhtml"
        assertEquals("OEBPS/chapter1.xhtml", normalizeEpubHref(url))
    }

    @Test
    fun `normalize URL-encoded path decodes via URI path`() {
        val encoded = "Text/Martin%2C%20George%20R.%20R.%20-%20Song%20of%20Ice%20and%20Fire%2001%20-%20A%20Game%20of%20Thrones_split_000.htm"
        val result = normalizeEpubHref(encoded)
        assertEquals("Text/Martin, George R. R. - Song of Ice and Fire 01 - A Game of Thrones_split_000.htm", result)
    }

    @Test
    fun `normalize already normalized relative path is unchanged`() {
        assertEquals("Text/Section0061.xhtml", normalizeEpubHref("Text/Section0061.xhtml"))
    }

    // epubCfiToSpineIndex tests

    @Test
    fun `step 2 returns spine index 0`() {
        assertEquals(0, epubCfiToSpineIndex("epubcfi(/6/2!/4/2)"))
    }

    @Test
    fun `step 4 returns spine index 1`() {
        assertEquals(1, epubCfiToSpineIndex("epubcfi(/6/4!/4/2)"))
    }

    @Test
    fun `step 160 returns spine index 79`() {
        assertEquals(79, epubCfiToSpineIndex("epubcfi(/6/160!/4/2)"))
    }

    @Test
    fun `full CFI with element ID and character offset - step 160`() {
        assertEquals(79, epubCfiToSpineIndex("epubcfi(/6/160!/4/4[heading_id_2]/1:0)"))
    }

    @Test
    fun `full CFI with character offset - step 24`() {
        assertEquals(11, epubCfiToSpineIndex("epubcfi(/6/24!/4/2/1:42)"))
    }

    @Test
    fun `empty string returns null`() {
        assertEquals(null, epubCfiToSpineIndex(""))
    }

    @Test
    fun `malformed CFI without slash-6 returns null`() {
        assertEquals(null, epubCfiToSpineIndex("epubcfi(/4/2!/4/2)"))
    }

    @Test
    fun `odd step returns null`() {
        assertEquals(null, epubCfiToSpineIndex("epubcfi(/6/3!/4/2)"))
    }

    @Test
    fun `step 0 returns null`() {
        assertEquals(null, epubCfiToSpineIndex("epubcfi(/6/0!/4/2)"))
    }

    @Test
    fun `non-epubcfi string returns null`() {
        assertEquals(null, epubCfiToSpineIndex("some-text"))
    }

    @Test
    fun `step 999 returns 499`() {
        // step 999 is odd — should return null
        assertEquals(null, epubCfiToSpineIndex("epubcfi(/6/999!/4/2)"))
    }

    @Test
    fun `step 998 returns 498`() {
        assertEquals(498, epubCfiToSpineIndex("epubcfi(/6/998!/4/2)"))
    }

    // round-trip tests: buildEpubCfi + epubCfiToSpineIndex

    @Test
    fun `round-trip buildEpubCfi then epubCfiToSpineIndex for all items`() {
        for (i in readingOrder.indices) {
            val cfi = buildEpubCfi(readingOrder, readingOrder[i])
            assertEquals(i, epubCfiToSpineIndex(cfi))
        }
    }

    // buildEpubCfi with URL-encoded hrefs

    @Test
    fun `buildEpubCfi with URL-encoded href at index 1`() {
        val encodedHref = "Text/Martin%2C%20George%20R.%20R.%20-%20Song%20of%20Ice%20and%20Fire%2001%20-%20A%20Game%20of%20Thrones_split_000.htm"
        val encodedReadingOrder = listOf(
            "Text/titlepage.xhtml",
            encodedHref,
            "Text/Section0061.xhtml",
        )
        assertEquals("epubcfi(/6/4!/4/2)", buildEpubCfi(encodedReadingOrder, encodedHref))
    }
}

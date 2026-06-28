package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTocAdapterTest {

    @Test
    fun `flat outline projects each node to a TocEntry with page= href`() {
        val out = pdfOutlineToTocEntries(
            listOf(
                PdfOutlineNode("Intro", 0),
                PdfOutlineNode("Chapter 1", 10),
                PdfOutlineNode("Appendix", 200),
            )
        )
        assertEquals(3, out.size)
        assertEquals("Intro", out[0].title)
        assertEquals("page=0", out[0].href)
        assertEquals("page=10", out[1].href)
        assertEquals("page=200", out[2].href)
    }

    @Test
    fun `nested outline preserves children`() {
        val out = pdfOutlineToTocEntries(
            listOf(
                PdfOutlineNode(
                    "Part I", pageIndex = 5,
                    children = listOf(
                        PdfOutlineNode("Ch 1", 6),
                        PdfOutlineNode("Ch 2", 20),
                    ),
                ),
                PdfOutlineNode("Part II", 100),
            )
        )
        assertEquals(2, out.size)
        assertEquals("Part I", out[0].title)
        assertEquals(2, out[0].children.size)
        assertEquals("Ch 1", out[0].children[0].title)
        assertEquals("page=6", out[0].children[0].href)
        assertEquals("Ch 2", out[0].children[1].title)
        assertEquals("Part II", out[1].title)
        assertEquals(emptyList<Any>(), out[1].children)
    }

    @Test
    fun `flat rail entries flatten nested outline pre-order`() {
        val outline = listOf(
            PdfOutlineNode(
                "Part I", pageIndex = 5,
                children = listOf(
                    PdfOutlineNode("Ch 1", 6),
                    PdfOutlineNode(
                        "Ch 2", 20,
                        children = listOf(PdfOutlineNode("Ch 2.1", 25)),
                    ),
                ),
            ),
            PdfOutlineNode("Part II", 100),
        )
        val flat = pdfOutlineToFlatRailEntries(outline)
        assertEquals(
            listOf(
                PdfTocEntry("Part I", 5),
                PdfTocEntry("Ch 1", 6),
                PdfTocEntry("Ch 2", 20),
                PdfTocEntry("Ch 2.1", 25),
                PdfTocEntry("Part II", 100),
            ),
            flat,
        )
    }

    @Test
    fun `anonymous outline nodes are skipped on the rail but kept in TocEntries`() {
        // TocPanel collapses blank-titled rows by descending into children;
        // the rail simply omits them (no segment without a title).
        val outline = listOf(
            PdfOutlineNode(
                "", pageIndex = 0,
                children = listOf(PdfOutlineNode("Real chapter", 0)),
            )
        )
        val toc = pdfOutlineToTocEntries(outline)
        assertEquals(1, toc.size)
        assertEquals("", toc[0].title)
        assertEquals(1, toc[0].children.size)

        val flat = pdfOutlineToFlatRailEntries(outline)
        assertEquals(listOf(PdfTocEntry("Real chapter", 0)), flat)
    }

    @Test
    fun `pdfActiveHref renders the matching synthetic href`() {
        val toc = pdfOutlineToTocEntries(listOf(PdfOutlineNode("X", 42)))
        assertEquals(toc[0].href, pdfActiveHref(42))
    }

    @Test
    fun `empty outline projects to empty lists`() {
        assertEquals(0, pdfOutlineToTocEntries(emptyList()).size)
        assertEquals(0, pdfOutlineToFlatRailEntries(emptyList()).size)
    }
}

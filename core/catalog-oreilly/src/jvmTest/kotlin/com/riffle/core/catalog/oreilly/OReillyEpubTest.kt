package com.riffle.core.catalog.oreilly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class OReillyEpubTest {

    @Test
    fun `self-closes void elements so the fragment parses as XML`() {
        val raw = """<img src="a.png" alt="" width="406" height="406">text<br>more<hr>"""
        val fixed = OReillyEpub.selfCloseVoidElements(raw)
        assertEquals("""<img src="a.png" alt="" width="406" height="406"/>text<br/>more<hr/>""", fixed)
    }

    @Test
    fun `already self-closed void elements are left intact`() {
        assertEquals("<br/><img src=\"x\"/>", OReillyEpub.selfCloseVoidElements("<br/><img src=\"x\"/>"))
    }

    @Test
    fun `wrapped chapter with an unclosed img is well-formed XML`() {
        val body = """<div id="sbo-rt-content"><figure><img src="fig.png" alt=""></figure><p>Hi &amp; bye</p></div>"""
        val xhtml = OReillyEpub.wrapChapter("Ch 1", body, listOf("styles/book.css"))
        // Parses without throwing => well-formed XML (the whole point — Readium uses a strict parser).
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(xhtml.byteInputStream())
        assertTrue(doc.documentElement.tagName == "html")
    }

    @Test
    fun `selfCloseVoidElements handles gt-sign inside a double-quoted attribute value`() {
        // Regression: [^>]*? stopped at the > inside the attribute, producing truncated/broken output.
        val input = "<img alt=\"a > b\" src=\"fig.png\">"
        val result = OReillyEpub.selfCloseVoidElements(input)
        assertEquals("<img alt=\"a > b\" src=\"fig.png\"/>", result)
    }

    @Test
    fun `selfCloseVoidElements handles gt-sign inside a single-quoted attribute value`() {
        val input = "<input placeholder='x > y'>"
        val result = OReillyEpub.selfCloseVoidElements(input)
        assertEquals("<input placeholder='x > y'/>", result)
    }

    @Test
    fun `relative prefix walks out of subdirectories`() {
        assertEquals("", OReillyEpub.relPrefixFor("cover.xhtml"))
        assertEquals("../", OReillyEpub.relPrefixFor("xhtml/cover.xhtml"))
        assertEquals("../../", OReillyEpub.relPrefixFor("a/b/c.xhtml"))
        assertEquals("../images/x.jpg", OReillyEpub.relativeTo("xhtml/ch.xhtml", "images/x.jpg"))
    }
}

package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubHrefTest {

    @Test
    fun `collapses dot-dot so a SMIL-relative ref matches a spine href`() {
        assertEquals("text/part6.html#s0", resolveEpubHref("../text/part6.html#s0"))
        assertEquals("text/part6.html", resolveEpubHref("text/part6.html"))
    }

    @Test
    fun `resolves against a base folder, preserving the fragment`() {
        // base "OEBPS/smil" + "../audio/c1.mp3" → pop "smil" → "OEBPS/audio/c1.mp3"
        assertEquals("OEBPS/audio/c1.mp3", resolveEpubHref("../audio/c1.mp3", base = "OEBPS/smil"))
        // base "MediaOverlays" + "../text/x.html#id" → "../" escapes the single base segment → "text/x.html#id"
        assertEquals("text/x.html#id", resolveEpubHref("../text/x.html#id", base = "MediaOverlays"))
    }

    @Test
    fun `a root-relative href ignores the base and drops the leading slash`() {
        assertEquals("text/x.html", resolveEpubHref("/text/x.html", base = "OEBPS/smil"))
    }

    @Test
    fun `dot-dot that would escape the root is dropped`() {
        assertEquals("text/x.html", resolveEpubHref("../../text/x.html"))
    }

    @Test
    fun `current-directory segments are removed`() {
        assertEquals("text/x.html#s1", resolveEpubHref("./text/./x.html#s1"))
    }
}

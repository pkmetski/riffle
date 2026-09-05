package com.riffle.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Coverage for [resolveEpubHref]/[normalizeEpubHref]/[epubCfiToSpineIndex], which moved to
 * `commonMain` when the rail-segment logic was shared with iOS. Living in `commonTest` means these
 * run on `iosSimulatorArm64Test` too — the interesting case is [normalizeEpubHref]'s URL-path
 * branch, which delegates to the platform `uriPath` actual (`java.net.URI` on JVM, `NSURL` on iOS),
 * so running these on-device proves the two actuals agree on the shapes the reader feeds them.
 */
class EpubHrefTest {

    // ── resolveEpubHref ───────────────────────────────────────────────────

    @Test
    fun `collapses dot-dot so a SMIL-relative ref matches a spine href`() {
        assertEquals("text/part6.html#s0", resolveEpubHref("../text/part6.html#s0"))
        assertEquals("text/part6.html", resolveEpubHref("text/part6.html"))
    }

    @Test
    fun `resolves against a base folder and preserves the fragment`() {
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

    // ── normalizeEpubHref (delegates to the platform uriPath actual) ───────

    @Test
    fun `normalizeEpubHref keeps an already-relative href`() {
        assertEquals("OEBPS/chapter1.xhtml", normalizeEpubHref("OEBPS/chapter1.xhtml"))
    }

    @Test
    fun `normalizeEpubHref strips a localhost origin to the resource path`() {
        assertEquals("OEBPS/chapter1.xhtml", normalizeEpubHref("http://localhost:8080/OEBPS/chapter1.xhtml"))
    }

    @Test
    fun `normalizeEpubHref takes the segment after a bang for archive URLs`() {
        assertEquals(
            "OEBPS/chapter1.xhtml",
            normalizeEpubHref("file:///path/to/book.epub!/OEBPS/chapter1.xhtml"),
        )
    }

    @Test
    fun `normalizeEpubHref drops a trailing fragment`() {
        assertEquals("chapter1.xhtml", normalizeEpubHref("chapter1.xhtml#section2"))
    }

    // ── epubCfiToSpineIndex ────────────────────────────────────────────────

    @Test
    fun `epubCfiToSpineIndex decodes the spine step`() {
        assertEquals(0, epubCfiToSpineIndex("epubcfi(/6/2!/4/2)"))
        assertEquals(2, epubCfiToSpineIndex("epubcfi(/6/6!/4/2)"))
    }

    @Test
    fun `epubCfiToSpineIndex rejects malformed input`() {
        assertNull(epubCfiToSpineIndex(""))
        assertNull(epubCfiToSpineIndex("not-a-cfi"))
        assertNull(epubCfiToSpineIndex("epubcfi(/6/3!/4/2)")) // odd step is invalid
    }
}

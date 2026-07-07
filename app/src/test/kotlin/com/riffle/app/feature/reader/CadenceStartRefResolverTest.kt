package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [resolveCadenceStartRef] — Cadence's page-top start-position resolver.
 *
 * Regression: without the per-chapter fallback, a null WebView probe (e.g. Readium decoration
 * overlays blocking `elementFromPoint`) fell through to the ticker's own "startIndex = 0"
 * default. That index points at cd-0 of the merged cross-chapter fragment history, i.e. the
 * first sentence of the FIRST chapter tokenised in the session — usually several pages behind
 * where the user actually is. Cadence would highlight that sentence, and Readium's decoration
 * engine would auto-scroll to reveal it — the "Cadence starts on a previous page" bug.
 *
 * If any assertion here flips, verify the fix in [EpubReaderViewModel.onCadencePageTopResolved]
 * hasn't been reverted before touching the tests.
 */
class CadenceStartRefResolverTest {

    private val chapterHrefs = linkedMapOf(
        // Session tokenised Ch2 first, then Ch3 (user opened Ch2, flipped to Ch3).
        "ch2.xhtml#cd-0" to "ch2.xhtml",
        "ch2.xhtml#cd-1" to "ch2.xhtml",
        "ch3.xhtml#cd-0" to "ch3.xhtml",
        "ch3.xhtml#cd-1" to "ch3.xhtml",
        "ch3.xhtml#cd-2" to "ch3.xhtml",
    )

    @Test
    fun `probed fragment id wins — happy path`() {
        val ref = resolveCadenceStartRef("ch3.xhtml", "cd-1", chapterHrefs)
        assertEquals("ch3.xhtml#cd-1", ref)
    }

    @Test
    fun `null probe falls back to first cd of the current chapter, not cd-0 of the merged history`() {
        // The bug: without this fallback the ticker starts at ch2.xhtml#cd-0 (cd-0 of the
        // merged history), which is on an earlier chapter. Assert we land on THIS chapter's
        // first fragment instead.
        val ref = resolveCadenceStartRef("ch3.xhtml", null, chapterHrefs)
        assertEquals("ch3.xhtml#cd-0", ref)
    }

    @Test
    fun `blank probe is treated the same as null probe`() {
        // WebView bridges sometimes surface JSON "" as a non-null empty string. Guard against
        // "$href#" being propagated to the ticker (which would look up an unknown fragment,
        // no-op the goTo, and fall through to cd-0 of the merged history — same regression).
        val ref = resolveCadenceStartRef("ch3.xhtml", "", chapterHrefs)
        assertEquals("ch3.xhtml#cd-0", ref)
    }

    @Test
    fun `null probe and chapter not yet tokenised returns null so caller can no-op the goTo`() {
        val ref = resolveCadenceStartRef("ch4.xhtml", null, chapterHrefs)
        assertNull(ref)
    }

    @Test
    fun `full ref probe wins over href arg — chapter-authoritative payload`() {
        // Regression: after the "chapter# prefix in the JS resolver payload" fix, the WebView
        // returns `"chapter#cd-N"` when the resolver could read the tokeniser-stamped chapter
        // attribute. Kotlin must use that verbatim — NOT concat href arg on top (which would
        // produce `"ch3.xhtml#ch2.xhtml#cd-1"`). This is the exact fix for the "Cadence starts
        // on Cover Design credits when the tokeniser lags Readium's locator by one chapter"
        // bug: with the chapter carried in the JS payload the resolved ref matches the
        // ticker's ordered list even when the Readium locator href points somewhere else.
        val ref = resolveCadenceStartRef(
            href = "ch3.xhtml",
            probedFragmentId = "ch2.xhtml#cd-1",
            chapterHrefs = chapterHrefs,
        )
        assertEquals("ch2.xhtml#cd-1", ref)
    }

    @Test
    fun `bare id probe with mismatched href but knownRefs guards against ticker goTo no-op`() {
        // Regression: the SPECIFIC bug from recording 20260707_155950 —
        //   Readium reports `currentLocatorHref = OEBPS/f02.xhtml` but the DOM's tokenised cd
        //   spans belong to `OEBPS/f01.xhtml` (Readium recycles the WebView; wv.chapterHref
        //   lags one chapter). The old resolver built `OEBPS/f02.xhtml#cd-0` — NOT in the
        //   ticker's ordered list, so `goTo` silently no-op'd and playback fell to
        //   `orderedFragments[0]` = `OEBPS/copyright.xhtml#cd-0` = "Cover design: Wiley".
        // Highlight decoration then applied on copyright.xhtml — a different chapter from
        // what the user was looking at — so no visible highlight and no scroll.
        //
        // Assertion: with knownRefs supplied, we detect the built ref isn't in the ticker's
        // ordered list and fall back to first-cd of any chapter tokenised for `href`; if that
        // fails too, return null (so caller skips goTo) instead of ferrying a broken ref.
        val knownRefs = chapterHrefs.keys // "ch3.xhtml#cd-0" etc.
        val ref = resolveCadenceStartRef(
            href = "ch4.xhtml", // not tokenised
            probedFragmentId = "cd-1", // bare id — would build "ch4.xhtml#cd-1", NOT in knownRefs
            chapterHrefs = chapterHrefs,
            knownRefs = knownRefs,
        )
        assertNull(ref)
    }

    @Test
    fun `bare id probe with matching href passes through when in knownRefs`() {
        // Sanity check: the knownRefs guard doesn't over-reject the normal happy path where
        // the current href actually is tokenised.
        val ref = resolveCadenceStartRef(
            href = "ch3.xhtml",
            probedFragmentId = "cd-1",
            chapterHrefs = chapterHrefs,
            knownRefs = chapterHrefs.keys,
        )
        assertEquals("ch3.xhtml#cd-1", ref)
    }
}

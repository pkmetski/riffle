package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [DefaultPositionTranslator] — pin the three coordinate round-trips
 * (CFI / audio seconds / Storyteller locator → canonical → back) and the SMIL-only
 * methods used by [AudiobookFollow]. Per issue #332 acceptance.
 *
 * Fixture: a two-chapter matched book where the ABS EPUB and Storyteller EPUB hold the
 * same prose; chapter 0 carries one narrated SMIL clip at 0.2 progression.
 */
class PositionTranslatorTest {

    private val ch0Html = """<html><body><p id="p0">${"x".repeat(100)}</p></body></html>"""
    private val ch1Html = """<html><body><p id="p1">${"y".repeat(100)}</p></body></html>"""
    private val absHrefs = listOf("c0.xhtml", "c1.xhtml")
    private val stHrefs = listOf("c0.xhtml", "c1.xhtml")

    private val translator = DefaultPositionTranslator(
        smilClips = listOf(
            MediaOverlayClip("c0.xhtml#s1", "a.mp3", clipBeginSec = 5.0, clipEndSec = 10.0),
            MediaOverlayClip("c1.xhtml#s2", "a.mp3", clipBeginSec = 10.0, clipEndSec = 15.0),
        ),
        crossEpubIndex = CrossEpubIndex(listOf(
            ChapterCharMap(absChars = 100, storytellerChars = 100),
            ChapterCharMap(absChars = 100, storytellerChars = 100),
        )),
        fragmentProgressions = mapOf(
            "c0.xhtml#s1" to ChapterProgression(0, 0.2),
            "c1.xhtml#s2" to ChapterProgression(1, 0.2),
        ),
        absSpineHrefs = absHrefs,
        absChapterHtml = { listOf(ch0Html, ch1Html).getOrNull(it) },
        storytellerSpineHrefs = stHrefs,
        storytellerChapterHtml = { listOf(ch0Html, ch1Html).getOrNull(it) },
    )

    // ── ABS CFI ↔ canonical ────────────────────────────────────────────────────

    @Test fun `absCfi round-trip stays within a one-character tolerance`() {
        // chapter 0, progression ~0.5 → epubcfi step 2, midway in p0
        val canonical = translator.absCfiToCanonical("epubcfi(/6/2!/4/2/1:50)")
        assertNotNull("canonical for known CFI", canonical)
        val recovered = translator.canonicalToAbsCfi(canonical!!)
        assertNotNull("CFI for canonical", recovered)
        // Re-derive progression from the recovered CFI and assert within ~1 char
        val originalProg = CanonicalReaderPosition(canonical).chapterProgression!!
        val docPath = extractCfiDocPath(recovered!!)!!
        val finalProg = cfiDocPathToProgression(docPath, ch0Html)!!
        assertEquals(originalProg, finalProg, 1.0 / 100.0)
    }

    @Test fun `absCfi for unknown spine returns null rather than guessing`() {
        assertNull(translator.absCfiToCanonical("epubcfi(/6/100!/4/2/1:0)"))
    }

    // ── audio seconds ↔ canonical ──────────────────────────────────────────────

    @Test fun `audio second mid-clip resolves to a canonical position on the displayed EPUB`() {
        val canonical = translator.audioSecondsToCanonical(7.5) // inside the c0 clip
        assertNotNull(canonical)
        assertEquals("c0.xhtml", CanonicalReaderPosition(canonical!!).href)
    }

    @Test fun `audio seconds round-trip returns the same instant within bundle precision`() {
        val canonical = translator.audioSecondsToCanonical(7.5)!!
        val back = translator.canonicalToAudioSeconds(canonical)
        assertEquals(5.0, back!!, 0.001) // fragmentAt snaps to the start of the narrated clip
    }

    @Test fun `audio second outside any clip returns null`() {
        assertNull(translator.audioSecondsToCanonical(999.0))
    }

    // ── Storyteller locator ↔ canonical ────────────────────────────────────────

    @Test fun `storyteller locator round-trips through canonical`() {
        val stLocator = """{"href":"c0.xhtml","locations":{"progression":0.5}}"""
        val canonical = translator.storytellerLocatorToCanonical(stLocator)
        assertNotNull(canonical)
        val back = translator.canonicalToStorytellerLocator(canonical!!)
        assertNotNull(back)
        assertEquals("c0.xhtml", CanonicalReaderPosition(back!!).href)
        assertEquals(0.5, CanonicalReaderPosition(back).chapterProgression!!, 0.001)
    }

    // ── SMIL-only API used by AudiobookFollow ─────────────────────────────────

    @Test fun `bundle-only translator answers the SMIL questions`() {
        val bundleOnly = DefaultPositionTranslator(
            smilClips = listOf(
                MediaOverlayClip("c0.xhtml#s1", "a.mp3", clipBeginSec = 5.0, clipEndSec = 10.0),
            ),
        )
        assertEquals(5.0, bundleOnly.fragmentRefToAudioSeconds("c0.xhtml#s1")!!, 0.001)
        assertEquals("c0.xhtml#s1", bundleOnly.audioSecondsToTextFragment(7.0))
        // Without spine/HTML the locator methods degrade to null rather than guessing.
        assertNull(bundleOnly.audioSecondsToCanonical(7.0))
        assertNull(bundleOnly.absCfiToCanonical("epubcfi(/6/2!/4/2/1:0)"))
    }

    // ── Per-file SMIL absolute offsets (regression for audiobook double-count) ─

    @Test fun `multi-file SMIL absolutises clips by cumulative prior-file duration`() {
        val t = DefaultPositionTranslator(
            smilClips = listOf(
                MediaOverlayClip("c0.xhtml#a", "f1.mp3", clipBeginSec = 0.0, clipEndSec = 100.0),
                MediaOverlayClip("c1.xhtml#b", "f2.mp3", clipBeginSec = 0.0, clipEndSec = 50.0),
            ),
        )
        // The second file's clip begins at file-local 0s, which is absolute 100s.
        assertEquals(100.0, t.fragmentRefToAudioSeconds("c1.xhtml#b")!!, 0.001)
    }

    // ── displayedHref ↔ bundleHref ────────────────────────────────────────────

    @Test fun `displayedHrefToBundleHref returns the spine-aligned Storyteller href`() {
        assertEquals("c1.xhtml", translator.displayedHrefToBundleHref("c1.xhtml"))
        assertNull(translator.displayedHrefToBundleHref("not-in-spine.xhtml"))
    }

    // ── canonicalBookProgress (per-chapter character weighting) ────────────────

    @Test fun `canonicalBookProgress falls back to chapter-char weighting when totalProgression is absent`() {
        // First chapter, mid-chapter → ~25% of the book (chapter weight 1/2, within-chapter 0.5)
        val canonical = """{"href":"c0.xhtml","locations":{"progression":0.5}}"""
        val p = translator.canonicalBookProgress(canonical)
        assertTrue("expected ~0.25, got $p", p in 0.2f..0.3f)
    }
}

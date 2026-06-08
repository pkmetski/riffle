package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanonicalPositionTranslatorTest {

    private val clips = listOf(
        MediaOverlayClip("chapter1.xhtml#sent1", "audio/ch1.mp3", clipBeginSec = 0.0, clipEndSec = 3.5),
        MediaOverlayClip("chapter1.xhtml#sent2", "audio/ch1.mp3", clipBeginSec = 3.5, clipEndSec = 7.0),
        MediaOverlayClip("chapter2.xhtml#sent1", "audio/ch2.mp3", clipBeginSec = 7.0, clipEndSec = 10.0),
    )

    @Test
    fun `audio seconds map to the text fragment whose clip covers that time`() {
        val translator = CanonicalPositionTranslator(smilClips = clips)

        assertEquals("chapter1.xhtml#sent2", translator.audioSecondsToTextFragment(4.0))
    }

    @Test
    fun `audio seconds before any clip are unmappable`() {
        val translator = CanonicalPositionTranslator(smilClips = clips)

        assertNull(translator.audioSecondsToTextFragment(-1.0))
    }

    @Test
    fun `audio seconds past the last clip are unmappable`() {
        val translator = CanonicalPositionTranslator(smilClips = clips)

        assertNull(translator.audioSecondsToTextFragment(100.0))
    }

    @Test
    fun `a text fragment maps to the start of its narrating clip`() {
        val translator = CanonicalPositionTranslator(smilClips = clips)

        assertEquals(3.5, translator.textFragmentToAudioSeconds("chapter1.xhtml#sent2")!!, 0.0001)
    }

    @Test
    fun `a text fragment with no narrating clip is unmappable`() {
        val translator = CanonicalPositionTranslator(smilClips = clips)

        assertNull(translator.textFragmentToAudioSeconds("chapter9.xhtml#nope"))
    }

    // ── Cross-EPUB progression mapping ─────────────────────────────────────────
    // A within-chapter progression is a readable-character offset divided by the
    // chapter's character count. Mapping across the two EPUBs preserves the absolute
    // readable-character offset, so progression scales by the chapters' count ratio.

    private val index = CrossEpubIndex(
        perChapter = listOf(
            ChapterCharMap(absChars = 200, storytellerChars = 100),
        ),
    )

    @Test
    fun `Storyteller progression maps to ABS progression preserving character offset`() {
        val translator = CanonicalPositionTranslator(smilClips = clips, index = index)

        // st progression 0.5 → offset 50 chars → abs progression 50/200 = 0.25
        val abs = translator.storytellerToAbsProgression(ChapterProgression(0, 0.5))

        assertEquals(ChapterProgression(0, 0.25), abs)
    }

    @Test
    fun `ABS progression maps back to Storyteller progression`() {
        val translator = CanonicalPositionTranslator(smilClips = clips, index = index)

        // abs progression 0.25 → offset 50 chars → st progression 50/100 = 0.5
        val st = translator.absToStorytellerProgression(ChapterProgression(0, 0.25))

        assertEquals(ChapterProgression(0, 0.5), st)
    }

    @Test
    fun `a chapter index with no entry in the cross-EPUB index is unmappable`() {
        val translator = CanonicalPositionTranslator(smilClips = clips, index = index)

        assertNull(translator.storytellerToAbsProgression(ChapterProgression(9, 0.5)))
    }

    @Test
    fun `book-wide progress weights chapters by their character counts`() {
        // Three chapters of 100, 300, 100 abs chars (total 500). Halfway through chapter 2 (index 1)
        // is 100 + 0.5*300 = 250 chars → 250/500 = 0.5 of the book.
        val translator = CanonicalPositionTranslator(
            smilClips = emptyList(),
            index = CrossEpubIndex(
                listOf(
                    ChapterCharMap(absChars = 100, storytellerChars = 100),
                    ChapterCharMap(absChars = 300, storytellerChars = 300),
                    ChapterCharMap(absChars = 100, storytellerChars = 100),
                ),
            ),
        )

        assertEquals(0.5, translator.absBookProgression(ChapterProgression(1, 0.5))!!, 1e-9)
        assertEquals(0.0, translator.absBookProgression(ChapterProgression(0, 0.0))!!, 1e-9)
        assertEquals(1.0, translator.absBookProgression(ChapterProgression(2, 1.0))!!, 1e-9)
    }

    @Test
    fun `book-wide progress is null when the index has no character data`() {
        val translator = CanonicalPositionTranslator(smilClips = emptyList())
        assertNull(translator.absBookProgression(ChapterProgression(0, 0.5)))
    }

    // ── Audio-seconds ↔ canonical (Storyteller) progression ────────────────────
    // Composes the SMIL clip lookup with each fragment's resolved within-chapter
    // progression in the Storyteller EPUB.

    private val fragmentProgressions = mapOf(
        "chapter1.xhtml#sent1" to ChapterProgression(0, 0.0),
        "chapter1.xhtml#sent2" to ChapterProgression(0, 0.4),
        "chapter2.xhtml#sent1" to ChapterProgression(1, 0.0),
    )

    @Test
    fun `audio seconds map to the canonical progression of the narrated fragment`() {
        val translator = CanonicalPositionTranslator(
            smilClips = clips,
            fragmentProgressions = fragmentProgressions,
        )

        assertEquals(
            ChapterProgression(0, 0.4),
            translator.audioSecondsToStorytellerProgression(4.0),
        )
    }

    @Test
    fun `audio seconds are unmappable when the narrated fragment has no resolved progression`() {
        val translator = CanonicalPositionTranslator(
            smilClips = clips,
            fragmentProgressions = emptyMap(),
        )

        assertNull(translator.audioSecondsToStorytellerProgression(4.0))
    }

    @Test
    fun `a canonical progression maps to the audio time of the fragment it falls in`() {
        val translator = CanonicalPositionTranslator(
            smilClips = clips,
            fragmentProgressions = fragmentProgressions,
        )

        // progression 0.5 in chapter 0 falls in sent2 (0.4), whose clip begins at 3.5s
        assertEquals(3.5, translator.storytellerProgressionToAudioSeconds(ChapterProgression(0, 0.5))!!, 0.0001)
    }

    @Test
    fun `a canonical progression before any narrated fragment is unmappable to audio`() {
        val translator = CanonicalPositionTranslator(
            smilClips = clips,
            fragmentProgressions = fragmentProgressions,
        )

        assertNull(translator.storytellerProgressionToAudioSeconds(ChapterProgression(5, 0.5)))
    }
}

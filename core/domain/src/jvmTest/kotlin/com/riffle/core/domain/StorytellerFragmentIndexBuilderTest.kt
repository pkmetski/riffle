package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StorytellerFragmentIndexBuilderTest {

    @Test
    fun `resolves each SMIL fragment to its chapter index and within-chapter progression`() {
        val chapters = listOf(
            EpubChapterHtml(
                href = "chapter1.xhtml",
                html = "<html><body><p id=\"sent1\">AAAA</p><p id=\"sent2\">BBBB</p></body></html>",
            ),
            EpubChapterHtml(
                href = "chapter2.xhtml",
                html = "<html><body><p id=\"sent1\">CCCC</p></body></html>",
            ),
        )
        val clips = listOf(
            MediaOverlayClip("chapter1.xhtml#sent1", "a.mp3", 0.0, 1.0),
            MediaOverlayClip("chapter1.xhtml#sent2", "a.mp3", 1.0, 2.0),
            MediaOverlayClip("chapter2.xhtml#sent1", "b.mp3", 2.0, 3.0),
        )

        val map = StorytellerFragmentIndexBuilder.build(chapters, clips)

        assertEquals(
            mapOf(
                "chapter1.xhtml#sent1" to ChapterProgression(0, 0.0),
                "chapter1.xhtml#sent2" to ChapterProgression(0, 0.5),
                "chapter2.xhtml#sent1" to ChapterProgression(1, 0.0),
            ),
            map,
        )
    }

    @Test
    fun `resolves SMIL refs written relative to the SMIL directory (dot-dot prefix)`() {
        // Storyteller's SMIL files live in their own directory and reference the text files as
        // "../text/...", while the spine chapter hrefs are root-relative "text/...". The builder
        // must resolve the relative path so the fragment maps to its chapter.
        val chapters = listOf(
            EpubChapterHtml(
                href = "text/part0006_split_001.html",
                html = "<html><body><p id=\"id191-s0\">AAAA</p><p id=\"id191-s1\">BBBB</p></body></html>",
            ),
        )
        val clips = listOf(
            MediaOverlayClip("../text/part0006_split_001.html#id191-s0", "a.mp3", 0.0, 1.0),
            MediaOverlayClip("../text/part0006_split_001.html#id191-s1", "a.mp3", 1.0, 2.0),
        )

        val map = StorytellerFragmentIndexBuilder.build(chapters, clips)

        assertEquals(
            mapOf(
                "../text/part0006_split_001.html#id191-s0" to ChapterProgression(0, 0.0),
                "../text/part0006_split_001.html#id191-s1" to ChapterProgression(0, 0.5),
            ),
            map,
        )
    }

    @Test
    fun `resolves each chapter's HTML once, not once per clip (perf regression guard)`() {
        // A readaloud chapter holds thousands of SMIL clips; parsing the chapter HTML per clip made
        // opening a matched book take tens of seconds. The builder must resolve each chapter once.
        val chapters = listOf(
            EpubChapterHtml("c1.xhtml", "<html><body><p id=\"a\">AAAA</p><p id=\"b\">BBBB</p></body></html>"),
            EpubChapterHtml("c2.xhtml", "<html><body><p id=\"a\">CCCC</p></body></html>"),
        )
        // 100 clips spread across chapter 1's two elements, plus one in chapter 2.
        val clips = (0 until 100).map { i ->
            MediaOverlayClip("c1.xhtml#${if (i % 2 == 0) "a" else "b"}", "x.mp3", i.toDouble(), i + 1.0)
        } + MediaOverlayClip("c2.xhtml#a", "x.mp3", 100.0, 101.0)

        var resolveCalls = 0
        StorytellerFragmentIndexBuilder.build(chapters, clips) { html, ids ->
            resolveCalls++
            EpubTextChars.progressionsOfElementIds(html, ids)
        }

        // One resolve (= one parse) per chapter with fragments — not one per clip (would be 101).
        assertEquals(2, resolveCalls)
    }

    @Test
    fun `skips fragments whose chapter or element cannot be resolved`() {
        val chapters = listOf(
            EpubChapterHtml("chapter1.xhtml", "<html><body><p id=\"sent1\">AAAA</p></body></html>"),
        )
        val clips = listOf(
            MediaOverlayClip("chapter1.xhtml#sent1", "a.mp3", 0.0, 1.0),
            MediaOverlayClip("chapter1.xhtml#ghost", "a.mp3", 1.0, 2.0), // id absent
            MediaOverlayClip("nochapter.xhtml#sent1", "a.mp3", 2.0, 3.0), // href absent
        )

        val map = StorytellerFragmentIndexBuilder.build(chapters, clips)

        assertEquals(mapOf("chapter1.xhtml#sent1" to ChapterProgression(0, 0.0)), map)
    }
}

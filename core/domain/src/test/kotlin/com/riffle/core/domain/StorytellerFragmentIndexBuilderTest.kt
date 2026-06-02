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

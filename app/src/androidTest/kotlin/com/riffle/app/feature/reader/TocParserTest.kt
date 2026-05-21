package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url

@RunWith(AndroidJUnit4::class)
class TocParserTest {

    @Test
    fun flatLinksMapToFlatTocEntries() {
        val links = listOf(
            Link(href = Url("chapter1.xhtml")!!, title = "Chapter 1"),
            Link(href = Url("chapter2.xhtml")!!, title = "Chapter 2"),
            Link(href = Url("chapter3.xhtml")!!, title = "Chapter 3"),
        )

        val entries = links.toTocEntries()

        assertEquals(3, entries.size)
        assertEquals("Chapter 1", entries[0].title)
        assertEquals("Chapter 2", entries[1].title)
        assertEquals("Chapter 3", entries[2].title)
        assertTrue(entries[0].children.isEmpty())
    }

    @Test
    fun nestedLinksMapToNestedTocEntries() {
        val links = listOf(
            Link(
                href = Url("chapter1.xhtml")!!, title = "Chapter 1",
                children = listOf(
                    Link(href = Url("chapter1.xhtml#s1")!!, title = "Section 1.1"),
                    Link(href = Url("chapter1.xhtml#s2")!!, title = "Section 1.2"),
                )
            ),
            Link(href = Url("chapter2.xhtml")!!, title = "Chapter 2"),
        )

        val entries = links.toTocEntries()

        assertEquals(2, entries.size)
        assertEquals("Chapter 1", entries[0].title)
        assertEquals(2, entries[0].children.size)
        assertEquals("Section 1.1", entries[0].children[0].title)
        assertEquals("Section 1.2", entries[0].children[1].title)
        assertTrue(entries[1].children.isEmpty())
    }

    @Test
    fun linkWithoutTitleUsesFallbackFromHref() {
        val links = listOf(
            Link(href = Url("chapter1.xhtml")!!, title = null),
        )

        val entries = links.toTocEntries()

        assertEquals(1, entries.size)
        assertTrue(entries[0].title.isNotBlank())
    }

    @Test
    fun hrefIsPreservedInTocEntry() {
        val link = Link(href = Url("chapter3.xhtml")!!, title = "Chapter 3")

        val entry = listOf(link).toTocEntries().first()

        assertTrue("Expected href to contain 'chapter3' but was '${entry.href}'", entry.href.contains("chapter3"))
    }
}

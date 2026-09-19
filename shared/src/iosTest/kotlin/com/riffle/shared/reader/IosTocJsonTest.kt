package com.riffle.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * iOS counterpart of `TocParserTest` (issue #1066), which pins Android's
 * `List<Link>.toTocEntries()`. Both platforms build the shared `TocEntry` tree from their own
 * Readium representation — Readium-Kotlin `Link`s on Android, the Swift bridge's TOC JSON here —
 * so the mapping needs a test on each side, and the four claims must match.
 *
 * `untitledEntryIsKeptWithABlankTitle` is a regression test: `parseTocArray` used to `continue` on
 * a missing title, which dropped the entry *and its entire subtree*. Nav documents routinely wrap
 * real chapters in an untitled grouping element, so those chapters silently disappeared from the
 * iOS TOC while Android rendered them.
 */
class IosTocJsonTest {

    @Test
    fun flatEntriesMapToFlatTocEntries() {
        val entries = parseTocJson(
            """
            [
              {"title":"Chapter 1","href":"chapter1.xhtml"},
              {"title":"Chapter 2","href":"chapter2.xhtml"},
              {"title":"Chapter 3","href":"chapter3.xhtml"}
            ]
            """.trimIndent(),
        )

        assertEquals(3, entries.size)
        assertEquals("Chapter 1", entries[0].title)
        assertEquals("Chapter 2", entries[1].title)
        assertEquals("Chapter 3", entries[2].title)
        assertTrue(entries[0].children.isEmpty())
    }

    @Test
    fun nestedEntriesMapToNestedTocEntries() {
        val entries = parseTocJson(
            """
            [
              {"title":"Chapter 1","href":"chapter1.xhtml","children":[
                {"title":"Section 1.1","href":"chapter1.xhtml#s1"},
                {"title":"Section 1.2","href":"chapter1.xhtml#s2"}
              ]},
              {"title":"Chapter 2","href":"chapter2.xhtml"}
            ]
            """.trimIndent(),
        )

        assertEquals(2, entries.size)
        assertEquals("Chapter 1", entries[0].title)
        assertEquals(2, entries[0].children.size)
        assertEquals("Section 1.1", entries[0].children[0].title)
        assertEquals("Section 1.2", entries[0].children[1].title)
        assertTrue(entries[1].children.isEmpty())
    }

    @Test
    fun untitledEntryIsKeptWithABlankTitle() {
        val entries = parseTocJson("""[{"href":"chapter1.xhtml"}]""")

        assertEquals(1, entries.size)
        assertEquals("", entries[0].title)
        assertTrue(entries[0].href.contains("chapter1"))
    }

    @Test
    fun anUntitledContainerKeepsItsChildren() {
        val entries = parseTocJson(
            """
            [
              {"href":"part1.xhtml","children":[
                {"title":"Chapter 1","href":"chapter1.xhtml"}
              ]}
            ]
            """.trimIndent(),
        )

        assertEquals(1, entries.size)
        assertEquals("", entries[0].title)
        assertEquals(1, entries[0].children.size)
        assertEquals("Chapter 1", entries[0].children[0].title)
    }

    @Test
    fun hrefIsPreservedInTocEntry() {
        val entries = parseTocJson("""[{"title":"Chapter 3","href":"chapter3.xhtml"}]""")

        assertTrue(
            entries.first().href.contains("chapter3"),
            "Expected href to contain 'chapter3' but was '${entries.first().href}'",
        )
    }

    @Test
    fun anEntryWithoutAnHrefIsSkipped() {
        val entries = parseTocJson("""[{"title":"No target"},{"title":"Chapter 1","href":"chapter1.xhtml"}]""")

        assertEquals(1, entries.size)
        assertEquals("Chapter 1", entries[0].title)
    }

    @Test
    fun malformedJsonYieldsNoEntries() {
        assertEquals(emptyList(), parseTocJson("not json"))
    }
}

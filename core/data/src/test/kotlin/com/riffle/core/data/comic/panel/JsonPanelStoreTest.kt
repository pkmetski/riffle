package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelRegion
import com.riffle.core.domain.comic.panel.PanelSource
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JsonPanelStoreTest {

    private lateinit var rootDir: File
    private lateinit var store: JsonPanelStore

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("panel-store-test").toFile()
        store = JsonPanelStore(rootDir)
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun `save then load returns the same page`() {
        val page = samplePage(0)
        store.save("book-1", page)
        assertEquals(page, store.load("book-1", 0))
    }

    @Test
    fun `load of unknown page returns null`() {
        assertNull(store.load("nonexistent", 0))
    }

    @Test
    fun `saveAll then loadAll returns full map`() {
        val pages = (0..5).map { samplePage(it) }
        store.saveAll("book-1", pages)
        val loaded = store.loadAll("book-1")
        assertEquals(6, loaded.size)
        for (p in pages) assertEquals(p, loaded[p.pageIndex])
    }

    @Test
    fun `save merges into existing book file without dropping prior pages`() {
        store.save("book-1", samplePage(0))
        store.save("book-1", samplePage(1))
        val loaded = store.loadAll("book-1")
        assertEquals(2, loaded.size)
        assertNotNull(loaded[0])
        assertNotNull(loaded[1])
    }

    @Test
    fun `save is idempotent for identical inputs`() {
        val page = samplePage(3)
        store.save("book-1", page)
        store.save("book-1", page)
        assertEquals(1, store.loadAll("book-1").size)
    }

    @Test
    fun `books are isolated by bookId`() {
        store.save("book-A", samplePage(0))
        assertNull(store.load("book-B", 0))
    }

    @Test
    fun `clear removes the on-disk file`() {
        store.save("book-1", samplePage(0))
        store.clear("book-1")
        assertNull(store.load("book-1", 0))
        assertTrue(store.loadAll("book-1").isEmpty())
    }

    @Test
    fun `unsafe characters in bookId are replaced in the filename but bookId is preserved`() {
        val bookId = "abs::/library/item id with spaces"
        val page = samplePage(0)
        store.save(bookId, page)

        // The file's actual name has no unsafe characters.
        val files = rootDir.listFiles().orEmpty()
        assertEquals(1, files.size)
        assertTrue(
            "filename should not contain unsafe characters: ${files[0].name}",
            files[0].name.matches(Regex("[A-Za-z0-9._-]+")),
        )
        // But the bookId round-trips.
        assertEquals(page, store.load(bookId, 0))
    }

    @Test
    fun `filename collision with a different bookId does not return the wrong data`() {
        // Two book ids that map to the same safe filename because the difference is in unsafe chars.
        val idA = "abs::/library/item-1"
        val idB = "abs__/library/item-1"
        store.save(idA, samplePage(0))
        // Loading with a different bookId that maps to the same file must return empty.
        assertTrue(store.loadAll(idB).isEmpty())
    }

    @Test
    fun `on-disk file written with an older schema version is treated as a miss`() {
        // Simulate a v1 (pre-fix) cache file with a Fallback result — the exact failure that
        // stranded users on the whole-page reader after the detector algorithm was fixed but
        // the cache still returned Fallback.
        val file = File(rootDir, "book-1.json")
        val legacyJson = """
            {"schemaVersion":4,"bookId":"book-1","pages":[
              {"pageIndex":0,"imageWidth":400,"imageHeight":560,
               "panels":[{"x":0,"y":0,"width":400,"height":560}],"source":"Fallback"}
            ]}
        """.trimIndent()
        file.writeText(legacyJson)

        // load / loadAll should NOT return the stale Fallback — caller re-detects.
        assertNull(store.load("book-1", 0))
        assertTrue(store.loadAll("book-1").isEmpty())
    }

    @Test
    fun `a file without any schemaVersion field is treated as version 1 and rejected`() {
        // Some early builds wrote no schema field at all. kotlinx-serialization defaults the
        // property to 1, which mismatches the current version → treated as a miss.
        val file = File(rootDir, "book-1.json")
        file.writeText("""{"bookId":"book-1","pages":[]}""")
        assertNull(store.load("book-1", 0))
    }

    @Test
    fun `saving stamps the current schema version so reloads are cache-hits`() {
        val page = samplePage(0)
        store.save("book-1", page)
        // File must be tagged with the current version and reload cleanly.
        assertEquals(page, store.load("book-1", 0))
        val text = File(rootDir, "book-1.json").readText()
        assertTrue(
            "expected \"schemaVersion\":${JsonPanelStore.CURRENT_SCHEMA_VERSION} in $text",
            text.contains("\"schemaVersion\":${JsonPanelStore.CURRENT_SCHEMA_VERSION}"),
        )
    }

    @Test
    fun `Fallback page round-trips`() {
        val fallback = PagePanels(
            pageIndex = 2,
            imageWidth = 800,
            imageHeight = 1200,
            panels = listOf(PanelRegion(0, 0, 800, 1200)),
            source = PanelSource.Fallback,
        )
        store.save("book-1", fallback)
        assertEquals(fallback, store.load("book-1", 2))
    }

    private fun samplePage(index: Int): PagePanels = PagePanels(
        pageIndex = index,
        imageWidth = 400,
        imageHeight = 560,
        panels = listOf(
            PanelRegion(x = 20, y = 20, width = 170, height = 250),
            PanelRegion(x = 210, y = 20, width = 170, height = 250),
        ),
        source = PanelSource.Auto,
    )
}

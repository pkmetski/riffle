package com.riffle.app.feature.source.oreilly

import com.riffle.core.catalog.LazyAssetFile
import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.core.catalog.LazySpineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Unit tests for [OReillyLazyContainer] cache helpers and [buildChapterXhtml].
 *
 * These tests target the companion-object helpers and static logic (cache key derivation,
 * atomic write, XHTML assembly) rather than the full Readium Container/Resource hierarchy,
 * which requires android.net.Uri unavailable in JVM unit tests.
 */
class OReillyLazyContainerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun makePub(
        vararg paths: String,
        assetFiles: List<LazyAssetFile> = emptyList(),
    ): LazyPublicationShape {
        val spine = paths.mapIndexed { i, p ->
            LazySpineItem(i, p, "Chapter ${i + 1}", 10_000L, "application/xhtml+xml")
        }
        return LazyPublicationShape(
            bookId = "9781234567890",
            identifier = "urn:orm:book:9781234567890",
            title = "Test Book",
            language = "en",
            spine = spine,
            absoluteFilesPrefix = "https://oreilly.com/api/v2/epubs/urn:orm:book:9781234567890/files/",
            pathFilesPrefix = "/api/v2/epubs/urn:orm:book:9781234567890/files/",
            cssFullPaths = listOf("styles/main.css"),
            assetFiles = assetFiles,
        )
    }

    // ---- cacheFileFor -------------------------------------------------------

    @Test
    fun `cacheFileFor produces stable path for bookId and fullPath`() {
        val cacheDir = tmp.newFolder()
        val f1 = OReillyLazyContainer.cacheFileFor(cacheDir, "9781234567890", "xhtml/ch01.xhtml")
        val f2 = OReillyLazyContainer.cacheFileFor(cacheDir, "9781234567890", "xhtml/ch01.xhtml")
        assertEquals(f1.absolutePath, f2.absolutePath)
    }

    @Test
    fun `cacheFileFor produces different paths for different chapters`() {
        val cacheDir = tmp.newFolder()
        val f1 = OReillyLazyContainer.cacheFileFor(cacheDir, "9781234567890", "xhtml/ch01.xhtml")
        val f2 = OReillyLazyContainer.cacheFileFor(cacheDir, "9781234567890", "xhtml/ch02.xhtml")
        assertFalse(f1.absolutePath == f2.absolutePath)
    }

    @Test
    fun `cacheFileFor sanitizes slashes in fullPath`() {
        val cacheDir = tmp.newFolder()
        val f = OReillyLazyContainer.cacheFileFor(cacheDir, "id", "xhtml/ch01.xhtml")
        assertFalse(f.name.contains('/'))
    }

    // ---- writeCacheFile -----------------------------------------------------

    @Test
    fun `writeCacheFile writes bytes atomically and is readable`() {
        val cacheDir = tmp.newFolder()
        val target = OReillyLazyContainer.cacheFileFor(cacheDir, "book", "ch01.xhtml")
        val content = "hello world".encodeToByteArray()
        OReillyLazyContainer.writeCacheFile(target, content)
        assertTrue(target.exists())
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `writeCacheFile creates parent directories`() {
        val cacheDir = tmp.newFolder()
        val target = OReillyLazyContainer.cacheFileFor(cacheDir, "nested/book", "ch01.xhtml")
        assertFalse(target.parentFile!!.exists())
        OReillyLazyContainer.writeCacheFile(target, ByteArray(0))
        assertTrue(target.parentFile!!.exists())
    }

    // ---- buildChapterXhtml --------------------------------------------------

    @Test
    fun `buildChapterXhtml rewrites absolute URL prefix to relative`() {
        val pub = makePub("xhtml/ch01.xhtml")
        val item = pub.spine[0]
        val rawHtml = "<p>See <a href=\"https://oreilly.com/api/v2/epubs/urn:orm:book:9781234567890/files/images/fig.png\">fig</a></p>"
        val xhtml = OReillyLazyContainer.buildChapterXhtml(pub, item, rawHtml)
        assertFalse(xhtml.contains("https://oreilly.com/api/v2"))
        assertTrue(xhtml.contains("../images/fig.png"))
    }

    @Test
    fun `buildChapterXhtml rewrites path prefix to relative`() {
        val pub = makePub("xhtml/ch01.xhtml")
        val item = pub.spine[0]
        val rawHtml = "<img src=\"/api/v2/epubs/urn:orm:book:9781234567890/files/images/x.jpg\"/>"
        val xhtml = OReillyLazyContainer.buildChapterXhtml(pub, item, rawHtml)
        assertFalse(xhtml.contains("/api/v2/epubs"))
        assertTrue(xhtml.contains("../images/x.jpg"))
    }

    @Test
    fun `buildChapterXhtml injects stylesheet link`() {
        val pub = makePub("xhtml/ch01.xhtml")
        val item = pub.spine[0]
        val xhtml = OReillyLazyContainer.buildChapterXhtml(pub, item, "<p>body</p>")
        assertTrue(xhtml.contains("styles/main.css") || xhtml.contains("../styles/main.css"))
    }

    @Test
    fun `buildChapterXhtml wraps content in valid XHTML structure`() {
        val pub = makePub("ch01.xhtml") // root-level chapter (no subdir)
        val item = pub.spine[0]
        val xhtml = OReillyLazyContainer.buildChapterXhtml(pub, item, "<p>Hello</p>")
        assertTrue(xhtml.startsWith("<?xml"))
        assertTrue(xhtml.contains("<html"))
        assertTrue(xhtml.contains("</html>"))
    }

    // ---- extractRelativePath ------------------------------------------------

    @Test
    fun `extractRelativePath strips readium_package origin from absolute URL`() {
        assertEquals(
            "assets/cover.png",
            OReillyLazyContainer.extractRelativePath("https://readium_package/assets/cover.png"),
        )
    }

    @Test
    fun `extractRelativePath strips readium_package origin for root-level asset`() {
        assertEquals(
            "epub.css",
            OReillyLazyContainer.extractRelativePath("https://readium_package/epub.css"),
        )
    }

    @Test
    fun `extractRelativePath leaves bare relative paths unchanged`() {
        assertEquals(
            "images/fig.png",
            OReillyLazyContainer.extractRelativePath("images/fig.png"),
        )
    }

    @Test
    fun `extractRelativePath strips leading slash from non-readium path`() {
        assertEquals(
            "xhtml/ch01.html",
            OReillyLazyContainer.extractRelativePath("/xhtml/ch01.html"),
        )
    }

    // ---- startBackgroundPrefetch --------------------------------------------

    @Test
    fun `startBackgroundPrefetch fetches and caches every uncached chapter`() = runBlocking {
        val cacheDir = tmp.newFolder()
        val pub = makePub("xhtml/ch01.xhtml", "xhtml/ch02.xhtml", "xhtml/ch03.xhtml")
        val fetched = mutableListOf<String>()
        val container = OReillyLazyContainer(
            pub = pub,
            cacheDir = cacheDir,
            fetchChapter = { _, path, _ -> fetched += path; "<p>content of $path</p>" },
            fetchAsset = { _, _ -> null },
            scope = CoroutineScope(Dispatchers.Unconfined),
            // android.net.Uri is unavailable in JVM unit tests; stub out the URL factory so
            // the container init doesn't crash. startBackgroundPrefetch iterates pub.spine
            // directly and doesn't use the URL map, so this is safe for this test.
            urlFactory = { null },
        )

        // Pre-cache ch02 to verify it's skipped.
        val ch02Cache = OReillyLazyContainer.cacheFileFor(cacheDir, pub.bookId, "xhtml/ch02.xhtml")
        OReillyLazyContainer.writeCacheFile(ch02Cache, "<pre-cached>ch02</pre-cached>".encodeToByteArray())

        container.startBackgroundPrefetch(minDelayMs = 0L, maxDelayMs = 0L)

        // All three chapters must be cached on disk; only ch01 and ch03 were fetched from network.
        assertEquals(listOf("xhtml/ch01.xhtml", "xhtml/ch03.xhtml"), fetched)
        assertTrue(OReillyLazyContainer.cacheFileFor(cacheDir, pub.bookId, "xhtml/ch01.xhtml").exists())
        assertTrue(OReillyLazyContainer.cacheFileFor(cacheDir, pub.bookId, "xhtml/ch02.xhtml").exists())
        assertTrue(OReillyLazyContainer.cacheFileFor(cacheDir, pub.bookId, "xhtml/ch03.xhtml").exists())
    }

    @Test
    fun `startBackgroundPrefetch fetches assets and calls onAllCached with non-empty EPUB`() = runBlocking {
        val cacheDir = tmp.newFolder()
        val assetFiles = listOf(
            LazyAssetFile("styles/main.css", "text/css"),
            LazyAssetFile("images/cover.jpg", "image/jpeg"),
        )
        val pub = makePub("xhtml/ch01.xhtml", "xhtml/ch02.xhtml", assetFiles = assetFiles)
        val fetchedAssets = mutableListOf<String>()
        val container = OReillyLazyContainer(
            pub = pub,
            cacheDir = cacheDir,
            fetchChapter = { _, path, _ -> "<p>$path</p>" },
            fetchAsset = { _, path ->
                fetchedAssets += path
                "asset-$path".encodeToByteArray()
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
            urlFactory = { null },
        )

        var callbackBytes: ByteArray? = null
        container.startBackgroundPrefetch(minDelayMs = 0L, maxDelayMs = 0L, onAllCached = { callbackBytes = it })

        // Assets fetched from network.
        assertEquals(listOf("styles/main.css", "images/cover.jpg"), fetchedAssets)
        // Asset cache files written.
        assertTrue(OReillyLazyContainer.cacheFileFor(cacheDir, pub.bookId, "styles/main.css").exists())
        assertTrue(OReillyLazyContainer.cacheFileFor(cacheDir, pub.bookId, "images/cover.jpg").exists())
        // Callback invoked with a non-empty EPUB.
        assertNotNull(callbackBytes)
        assertTrue(callbackBytes!!.isNotEmpty())
        // The bytes form a readable ZIP starting with the mimetype entry.
        ZipInputStream(ByteArrayInputStream(callbackBytes)).use { zis ->
            val first = zis.nextEntry
            assertEquals("mimetype", first.name)
            assertEquals("application/epub+zip", zis.readBytes().decodeToString())
        }
    }

    @Test
    fun `startBackgroundPrefetch skips already-cached assets`() = runBlocking {
        val cacheDir = tmp.newFolder()
        val assetFiles = listOf(LazyAssetFile("styles/main.css", "text/css"))
        val pub = makePub("xhtml/ch01.xhtml", assetFiles = assetFiles)
        val fetchedAssets = mutableListOf<String>()

        // Pre-cache the asset.
        OReillyLazyContainer.writeCacheFile(
            OReillyLazyContainer.cacheFileFor(cacheDir, pub.bookId, "styles/main.css"),
            "pre-cached".encodeToByteArray(),
        )

        val container = OReillyLazyContainer(
            pub = pub,
            cacheDir = cacheDir,
            fetchChapter = { _, path, _ -> "<p>$path</p>" },
            fetchAsset = { _, path -> fetchedAssets += path; ByteArray(0) },
            scope = CoroutineScope(Dispatchers.Unconfined),
            urlFactory = { null },
        )
        container.startBackgroundPrefetch(minDelayMs = 0L, maxDelayMs = 0L)

        assertTrue("Pre-cached asset must not be re-fetched", fetchedAssets.isEmpty())
    }

    @Test
    fun `assembleEpubFromCache returns null when a chapter is missing`() {
        val cacheDir = tmp.newFolder()
        val pub = makePub("xhtml/ch01.xhtml", "xhtml/ch02.xhtml")
        val container = OReillyLazyContainer(
            pub = pub,
            cacheDir = cacheDir,
            fetchChapter = { _, _, _ -> "" },
            fetchAsset = { _, _ -> null },
            scope = CoroutineScope(Dispatchers.Unconfined),
            urlFactory = { null },
        )

        // Only ch01 cached; ch02 is missing.
        OReillyLazyContainer.writeCacheFile(
            OReillyLazyContainer.cacheFileFor(cacheDir, pub.bookId, "xhtml/ch01.xhtml"),
            "<p>ch01</p>".encodeToByteArray(),
        )

        assertNull(container.assembleEpubFromCache())
    }

    @Test
    fun `assembleEpubFromCache produces a valid EPUB when all chapters are cached`() {
        val cacheDir = tmp.newFolder()
        val pub = makePub("xhtml/ch01.xhtml", "xhtml/ch02.xhtml")
        val container = OReillyLazyContainer(
            pub = pub,
            cacheDir = cacheDir,
            fetchChapter = { _, _, _ -> "" },
            fetchAsset = { _, _ -> null },
            scope = CoroutineScope(Dispatchers.Unconfined),
            urlFactory = { null },
        )

        for (item in pub.spine) {
            OReillyLazyContainer.writeCacheFile(
                OReillyLazyContainer.cacheFileFor(cacheDir, pub.bookId, item.fullPath),
                "<p>${item.fullPath}</p>".encodeToByteArray(),
            )
        }

        val epub = container.assembleEpubFromCache()
        assertNotNull(epub)

        val entries = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(epub)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) { entries += entry.name; entry = zis.nextEntry }
        }
        assertTrue(entries.contains("mimetype"))
        assertTrue(entries.contains("OEBPS/content.opf"))
        assertTrue(entries.any { it.endsWith("ch01.xhtml") })
        assertTrue(entries.any { it.endsWith("ch02.xhtml") })
    }
}

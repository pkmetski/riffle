package com.riffle.app.feature.source.oreilly

import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.core.catalog.LazySpineItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [OReillyLazyContainer] cache helpers and [buildChapterXhtml].
 *
 * These tests target the companion-object helpers and static logic (cache key derivation,
 * atomic write, XHTML assembly) rather than the full Readium Container/Resource hierarchy,
 * which requires android.net.Uri unavailable in JVM unit tests.
 */
class OReillyLazyContainerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun makePub(vararg paths: String): LazyPublicationShape {
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
}

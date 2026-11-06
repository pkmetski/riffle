package com.riffle.app.feature.reader

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderChapterCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cache get() = ReaderChapterCache(tmp.root)

    @Test
    fun `load returns null when no cache exists`() {
        assertNull(cache.load("item1", "ch1.xhtml", "fp1"))
    }

    @Test
    fun `load returns null when fingerprint differs`() = runTest {
        cache.save("item1", "ch1.xhtml", "fp1", "hello".toByteArray(), 1000)
        assertNull(cache.load("item1", "ch1.xhtml", "fp2"))
    }

    @Test
    fun `load returns null when itemId differs`() = runTest {
        cache.save("item1", "ch1.xhtml", "fp1", "hello".toByteArray(), 1000)
        assertNull(cache.load("item2", "ch1.xhtml", "fp1"))
    }

    @Test
    fun `save and load round-trips bytes and height`() = runTest {
        val bytes = "<html>content</html>".toByteArray(Charsets.UTF_8)
        cache.save("item1", "ch1.xhtml", "fp1", bytes, 4500)
        val entry = cache.load("item1", "ch1.xhtml", "fp1")!!
        assert(bytes.contentEquals(entry.xhtmlBytes))
        assertEquals(4500, entry.heightPx)
    }

    @Test
    fun `save overwrites previous entry`() = runTest {
        cache.save("item1", "ch1.xhtml", "fp1", "old".toByteArray(), 1000)
        cache.save("item1", "ch2.xhtml", "fp1", "new".toByteArray(), 2000)
        assertNull(cache.load("item1", "ch1.xhtml", "fp1"))
        assertNotNull(cache.load("item1", "ch2.xhtml", "fp1"))
    }
}

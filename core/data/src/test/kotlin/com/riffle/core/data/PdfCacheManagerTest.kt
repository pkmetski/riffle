package com.riffle.core.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfCacheManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var cacheDir: java.io.File
    private lateinit var manager: PdfCacheManagerImpl

    @Before
    fun setUp() {
        cacheDir = tmp.newFolder("cache")
        manager = PdfCacheManagerImpl(cacheDir)
    }

    @Test
    fun `cachePdf writes stream to cache dir`() = runTest {
        val bytes = "pdf-content".toByteArray()
        val file = manager.cachePdf("item-1", bytes.inputStream())
        assertTrue(file.exists())
        assertTrue(file.absolutePath.startsWith(cacheDir.absolutePath))
    }

    @Test
    fun `getCachedPdf returns file with correct content after caching`() = runTest {
        val bytes = "pdf-content".toByteArray()
        manager.cachePdf("item-1", bytes.inputStream())
        val result = manager.getCachedPdf("item-1")
        assertNotNull(result)
        assertArrayEquals(bytes, result!!.readBytes())
    }

    @Test
    fun `getCachedPdf returns null when item has not been cached`() {
        val result = manager.getCachedPdf("item-missing")
        assertNull(result)
    }

    @Test
    fun `evictAll removes all cached pdf files`() = runTest {
        manager.cachePdf("item-1", "pdf-a".toByteArray().inputStream())
        manager.cachePdf("item-2", "pdf-b".toByteArray().inputStream())

        manager.evictAll()

        assertNull(manager.getCachedPdf("item-1"))
        assertNull(manager.getCachedPdf("item-2"))
    }
}

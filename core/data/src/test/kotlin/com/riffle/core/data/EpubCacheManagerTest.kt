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

class EpubCacheManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var cacheDir: java.io.File
    private lateinit var downloadDir: java.io.File
    private lateinit var manager: EpubCacheManagerImpl

    @Before
    fun setUp() {
        cacheDir = tmp.newFolder("cache")
        downloadDir = tmp.newFolder("downloads")
        manager = EpubCacheManagerImpl(cacheDir)
    }

    @Test
    fun `cacheEpub writes file to cache dir`() = runTest {
        val bytes = "epub-content".toByteArray()
        val file = manager.cacheEpub("item-1", bytes.inputStream())
        assertTrue(file.exists())
        assertTrue(file.absolutePath.startsWith(cacheDir.absolutePath))
    }

    @Test
    fun `getCachedEpub returns file after it has been cached`() = runTest {
        val bytes = "epub-content".toByteArray()
        manager.cacheEpub("item-1", bytes.inputStream())
        val result = manager.getCachedEpub("item-1")
        assertNotNull(result)
        assertArrayEquals(bytes, result!!.readBytes())
    }

    @Test
    fun `getCachedEpub returns null when item has not been cached`() {
        val result = manager.getCachedEpub("item-missing")
        assertNull(result)
    }

    @Test
    fun `evictAll removes cached files but leaves download-tier files untouched`() = runTest {
        manager.cacheEpub("item-1", "epub-content".toByteArray().inputStream())
        val downloadFile = downloadDir.resolve("item-2.epub").also { it.writeBytes("download".toByteArray()) }

        manager.evictAll()

        assertNull(manager.getCachedEpub("item-1"))
        assertTrue(downloadFile.exists())
    }
}

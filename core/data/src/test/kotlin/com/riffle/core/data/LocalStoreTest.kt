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
import java.io.IOException

class LocalStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var storeDir: java.io.File
    private lateinit var otherDir: java.io.File
    private lateinit var store: LocalStoreImpl

    @Before
    fun setUp() {
        storeDir = tmp.newFolder("store")
        otherDir = tmp.newFolder("other")
        store = LocalStoreImpl(storeDir, ".epub")
    }

    @Test
    fun `get returns null when item is absent`() {
        assertNull(store.get("item-missing"))
    }

    @Test
    fun `save writes file to store directory`() = runTest {
        val bytes = "epub-content".toByteArray()
        val file = store.save("item-1", bytes.inputStream())
        assertTrue(file.exists())
        assertTrue(file.absolutePath.startsWith(storeDir.absolutePath))
    }

    @Test
    fun `get returns saved file with correct content`() = runTest {
        val bytes = "epub-content".toByteArray()
        store.save("item-1", bytes.inputStream())
        val result = store.get("item-1")
        assertNotNull(result)
        assertArrayEquals(bytes, result!!.readBytes())
    }

    @Test
    fun `save is atomic — no partial file left when stream throws`() = runTest {
        val failingStream = object : java.io.InputStream() {
            var bytesRead = 0
            override fun read(): Int {
                if (bytesRead++ > 4) throw IOException("simulated failure")
                return 'x'.code
            }
        }
        try {
            store.save("item-broken", failingStream)
        } catch (_: IOException) {}
        assertNull(store.get("item-broken"))
        assertTrue(storeDir.listFiles()?.none { it.name.contains("item-broken") } ?: true)
    }

    @Test
    fun `delete removes a previously saved file`() = runTest {
        store.save("item-1", "content".toByteArray().inputStream())
        store.delete("item-1")
        assertNull(store.get("item-1"))
    }

    @Test
    fun `clear removes all files in store dir without touching other directories`() = runTest {
        store.save("item-1", "a".toByteArray().inputStream())
        store.save("item-2", "b".toByteArray().inputStream())
        val sentinel = otherDir.resolve("sentinel.epub").also { it.writeBytes("x".toByteArray()) }

        store.clear()

        assertNull(store.get("item-1"))
        assertNull(store.get("item-2"))
        assertTrue(sentinel.exists())
    }

    @Test
    fun `listItemIds returns ids of all saved items`() = runTest {
        store.save("item-a", "a".toByteArray().inputStream())
        store.save("item-b", "b".toByteArray().inputStream())
        val ids = store.listItemIds()
        assertTrue(ids.containsAll(listOf("item-a", "item-b")))
        assertTrue(ids.size == 2)
    }

    @Test
    fun `listItemIds returns empty list when store is empty`() {
        assertTrue(store.listItemIds().isEmpty())
    }
}

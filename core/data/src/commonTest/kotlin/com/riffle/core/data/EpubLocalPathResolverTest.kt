package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.ContentCacheAccessStore
import com.riffle.core.domain.ContentCacheKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpubLocalPathResolverTest {

    private class FakeFileStore(
        private val presentFiles: Set<String> = emptySet(),
    ) : FileStore {
        override fun resolve(namespace: String, relativePath: String): String =
            "$namespace/$relativePath"

        override fun exists(namespace: String, relativePath: String): Boolean =
            "$namespace/$relativePath" in presentFiles
    }

    private class RecordingAccessStore : ContentCacheAccessStore {
        val accessed = mutableListOf<ContentCacheKey>()
        override suspend fun markAccessed(key: ContentCacheKey) {
            accessed += key
        }
        override suspend fun markAccessedAt(key: ContentCacheKey, timestampMs: Long) {
            accessed += key
        }
        override suspend fun lastAccessedAt(key: ContentCacheKey): Long? = null
        override suspend fun lastAccessedAtBulk(keys: Set<ContentCacheKey>): Map<ContentCacheKey, Long?> = emptyMap()
        override suspend fun forget(key: ContentCacheKey) {}
    }

    @Test
    fun downloadHitReturnsPathWithoutMarkAccessed() = runTest {
        val accessStore = RecordingAccessStore()
        val store = FakeFileStore(presentFiles = setOf("$NS_EPUB_DOWNLOADS/src/item.epub"))
        val resolver = EpubLocalPathResolver(store, accessStore)

        val result = resolver.resolve("src", "item")

        assertEquals("$NS_EPUB_DOWNLOADS/src/item.epub", result)
        assertEquals(0, accessStore.accessed.size, "downloads hit must NOT call markAccessed")
    }

    @Test
    fun cacheHitCallsMarkAccessedAndReturnsPath() = runTest {
        val accessStore = RecordingAccessStore()
        val store = FakeFileStore(presentFiles = setOf("$NS_EPUB_CACHE/src/item.epub"))
        val resolver = EpubLocalPathResolver(store, accessStore)

        val result = resolver.resolve("src", "item")

        assertEquals("$NS_EPUB_CACHE/src/item.epub", result)
        assertEquals(1, accessStore.accessed.size, "cache hit must call markAccessed once")
        assertEquals("src", accessStore.accessed.first().sourceId)
        assertEquals("item", accessStore.accessed.first().itemId)
    }

    @Test
    fun bothMissReturnsNull() = runTest {
        val accessStore = RecordingAccessStore()
        val resolver = EpubLocalPathResolver(FakeFileStore(), accessStore)

        val result = resolver.resolve("src", "item")

        assertNull(result)
        assertEquals(0, accessStore.accessed.size)
    }

    @Test
    fun downloadTakesPriorityOverCache() = runTest {
        val accessStore = RecordingAccessStore()
        val store = FakeFileStore(
            presentFiles = setOf(
                "$NS_EPUB_DOWNLOADS/src/item.epub",
                "$NS_EPUB_CACHE/src/item.epub",
            ),
        )
        val resolver = EpubLocalPathResolver(store, accessStore)

        val result = resolver.resolve("src", "item")

        assertEquals("$NS_EPUB_DOWNLOADS/src/item.epub", result)
        assertEquals(0, accessStore.accessed.size, "download wins, no markAccessed")
    }
}

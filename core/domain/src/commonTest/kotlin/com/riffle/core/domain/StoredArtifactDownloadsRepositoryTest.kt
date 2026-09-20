package com.riffle.core.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the Downloads policy both platforms now share. Every assertion here corresponds to a
 * divergence the iOS implementation had while Android was correct (#1071 §12):
 * PDF/CBZ/audiobook blindness, double-listing cached items that are also downloaded,
 * `removeDownload` leaving the cache copy behind, and stale offline badges.
 */
class StoredArtifactDownloadsRepositoryTest {

    @Test
    fun listsEveryMediaTypeNotJustEpub() {
        val repo = repo(
            downloads = listOf(
                store(StoredMediaType.Epub, artifact("src", "e1", StoredMediaType.Epub)),
                store(StoredMediaType.Pdf, artifact("src", "p1", StoredMediaType.Pdf)),
                store(StoredMediaType.Cbz, artifact("src", "c1", StoredMediaType.Cbz)),
                store(StoredMediaType.Audiobook, artifact("src", "a1", StoredMediaType.Audiobook)),
            ),
        )

        assertEquals(
            setOf(
                StoredItemArtifact("src", "e1", StoredMediaType.Epub),
                StoredItemArtifact("src", "p1", StoredMediaType.Pdf),
                StoredItemArtifact("src", "c1", StoredMediaType.Cbz),
                StoredItemArtifact("src", "a1", StoredMediaType.Audiobook),
            ),
            repo.getDownloadedArtifacts().toSet(),
        )
    }

    @Test
    fun cachedListExcludesItemsThatAreAlsoDownloaded() {
        val repo = repo(
            downloads = listOf(store(StoredMediaType.Epub, artifact("src", "both", StoredMediaType.Epub))),
            caches = listOf(
                store(
                    StoredMediaType.Epub,
                    artifact("src", "both", StoredMediaType.Epub),
                    artifact("src", "cached-only", StoredMediaType.Epub),
                ),
            ),
        )

        assertEquals(
            listOf(StoredItemArtifact("src", "cached-only", StoredMediaType.Epub)),
            repo.getCachedArtifacts(),
            "a downloaded item must not also be listed (and size-counted) as cached",
        )
    }

    @Test
    fun removeDownloadAlsoDeletesTheHiddenCacheCopy() = runTest {
        val download = store(StoredMediaType.Epub, artifact("src", "book", StoredMediaType.Epub))
        val cache = store(StoredMediaType.Epub, artifact("src", "book", StoredMediaType.Epub))
        val repo = repo(downloads = listOf(download), caches = listOf(cache))

        repo.removeDownload("src", "book")

        assertTrue(download.list().isEmpty(), "download copy must be gone")
        assertTrue(
            cache.list().isEmpty(),
            "cache copy must be gone too, or the item stays offline-available and reappears as Cached",
        )
    }

    @Test
    fun removeDownloadSweepsEveryMediaTypeNotJustTheOneThatMatched() = runTest {
        val epub = store(StoredMediaType.Epub, artifact("src", "book", StoredMediaType.Epub))
        val audiobook = store(StoredMediaType.Audiobook, artifact("src", "book", StoredMediaType.Audiobook))
        val repo = repo(downloads = listOf(epub, audiobook))

        repo.removeDownload("src", "book")

        assertTrue(epub.list().isEmpty())
        assertTrue(audiobook.list().isEmpty())
    }

    @Test
    fun singleItemRemovalsNotifyLocalAvailability() = runTest {
        val events = RecordingLocalAvailabilityEvents()
        val repo = repo(
            downloads = listOf(store(StoredMediaType.Epub, artifact("src", "down", StoredMediaType.Epub))),
            caches = listOf(store(StoredMediaType.Epub, artifact("src", "cached", StoredMediaType.Epub))),
            events = events,
        )

        repo.removeDownload("src", "down")
        repo.removeCached("src", "cached")

        assertEquals(
            listOf(StoredItemRef("src", "down"), StoredItemRef("src", "cached")),
            events.notified,
            "offline badges elsewhere in the app go stale without these notifications",
        )
    }

    @Test
    fun sizeOfSumsDownloadAndCacheStores() {
        val repo = repo(
            downloads = listOf(store(StoredMediaType.Epub, artifact("src", "book", StoredMediaType.Epub, 30L))),
            caches = listOf(store(StoredMediaType.Epub, artifact("src", "book", StoredMediaType.Epub, 12L))),
        )

        assertEquals(42L, repo.sizeOf("src", "book"))
    }

    @Test
    fun clearAllOnlyTouchesItsOwnSide() = runTest {
        val download = store(StoredMediaType.Epub, artifact("src", "d", StoredMediaType.Epub))
        val cache = store(StoredMediaType.Epub, artifact("src", "c", StoredMediaType.Epub))
        val repo = repo(downloads = listOf(download), caches = listOf(cache))

        repo.removeAllDownloads()
        assertTrue(download.list().isEmpty())
        assertEquals(1, cache.list().size)

        repo.clearAllCached()
        assertTrue(cache.list().isEmpty())
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private fun repo(
        downloads: List<FakeArtifactStore> = emptyList(),
        caches: List<FakeArtifactStore> = emptyList(),
        events: LocalAvailabilityEvents = RecordingLocalAvailabilityEvents(),
    ) = StoredArtifactDownloadsRepository(downloads, caches, events)

    private fun artifact(
        sourceId: String,
        itemId: String,
        mediaType: StoredMediaType,
        sizeBytes: Long = 1L,
    ) = FakeEntry(StoredItemArtifact(sourceId, itemId, mediaType), sizeBytes)

    private fun store(mediaType: StoredMediaType, vararg entries: FakeEntry) =
        FakeArtifactStore(mediaType, entries.toMutableList())

    private data class FakeEntry(val artifact: StoredItemArtifact, val sizeBytes: Long)

    private class FakeArtifactStore(
        override val mediaType: StoredMediaType,
        private val entries: MutableList<FakeEntry>,
    ) : StoredArtifactStore {
        override fun list(): List<StoredItemArtifact> = entries.map { it.artifact }

        override fun sizeOf(sourceId: String, itemId: String): Long =
            entries.filter { it.artifact.sourceId == sourceId && it.artifact.itemId == itemId }
                .sumOf { it.sizeBytes }

        override fun delete(sourceId: String, itemId: String) {
            entries.removeAll { it.artifact.sourceId == sourceId && it.artifact.itemId == itemId }
        }

        override fun clear() {
            entries.clear()
        }
    }

    private class RecordingLocalAvailabilityEvents : LocalAvailabilityEvents {
        val notified = mutableListOf<StoredItemRef>()
        override val changes: SharedFlow<StoredItemRef> = MutableSharedFlow()
        override fun notifyChanged(sourceId: String, itemId: String) {
            notified += StoredItemRef(sourceId, itemId)
        }
    }
}

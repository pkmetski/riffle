package com.riffle.core.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Moved from `jvmTest` to `commonTest` so it runs on Kotlin/Native too: [ContentCacheCleaner] was
 * JVM-only, which is why iOS's "auto-clear cache after N days" setting had no consumer at all
 * (#1071 §13). The scenarios and their expected [ContentCacheCleanResult]s are unchanged; the
 * real temp files became a fake [ContentCacheArtifactScanner] because deletion moved behind the
 * scanner seam.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentCacheCleanerTest {
    private val nowMs = 1_700_000_000_000L
    private val oldMs = nowMs - 40L * 24L * 60L * 60L * 1000L
    private val key = ContentCacheKey("source", "book/1", ContentCacheArtifactKind.Epub)

    @Test
    fun preExistingArtifactIsBackfilledAndNotDeletedOnFirstCleanup() = runTest {
        val scanner = scannerWithOneArtifact(lastModifiedAtMs = oldMs)
        val accessStore = InMemoryAccessStore()
        val cleaner = cleaner(
            autoClear = ContentCacheAutoClear.After30Days,
            accessStore = accessStore,
            scanner = scanner,
        )

        val result = cleaner.cleanExpired(nowMs)

        assertEquals(ContentCacheCleanResult(scanned = 1, backfilled = 1, removed = 0, freedBytes = 0), result)
        assertTrue(scanner.exists(key))
        assertEquals(oldMs, accessStore.lastAccessedAt(key))
    }

    @Test
    fun backfilledArtifactExpiresOnLaterCleanupWhenStillOld() = runTest {
        val scanner = scannerWithOneArtifact(lastModifiedAtMs = oldMs)
        val accessStore = InMemoryAccessStore().also { it.markAccessedAt(key, oldMs) }
        val cleaner = cleaner(
            autoClear = ContentCacheAutoClear.After30Days,
            accessStore = accessStore,
            scanner = scanner,
        )

        val result = cleaner.cleanExpired(nowMs)

        assertEquals(ContentCacheCleanResult(scanned = 1, backfilled = 0, removed = 1, freedBytes = 7L), result)
        assertTrue(!scanner.exists(key))
        assertEquals(null, accessStore.lastAccessedAt(key))
    }

    @Test
    fun offBackfillsButDoesNotDeleteOldArtifacts() = runTest {
        val scanner = scannerWithOneArtifact(lastModifiedAtMs = oldMs)
        val accessStore = InMemoryAccessStore().also { it.markAccessedAt(key, oldMs) }
        val cleaner = cleaner(
            autoClear = ContentCacheAutoClear.Off,
            accessStore = accessStore,
            scanner = scanner,
        )

        val result = cleaner.cleanExpired(nowMs)

        assertEquals(ContentCacheCleanResult(scanned = 1, backfilled = 0, removed = 0, freedBytes = 0), result)
        assertTrue(scanner.exists(key))
        assertEquals(oldMs, accessStore.lastAccessedAt(key))
    }

    @Test
    fun recentlyAccessedArtifactsAreKept() = runTest {
        val scanner = scannerWithOneArtifact(lastModifiedAtMs = oldMs)
        val recentMs = nowMs - 2L * 24L * 60L * 60L * 1000L
        val accessStore = InMemoryAccessStore().also { it.markAccessedAt(key, recentMs) }
        val cleaner = cleaner(
            autoClear = ContentCacheAutoClear.After7Days,
            accessStore = accessStore,
            scanner = scanner,
        )

        val result = cleaner.cleanExpired(nowMs)

        assertEquals(ContentCacheCleanResult(scanned = 1, backfilled = 0, removed = 0, freedBytes = 0), result)
        assertTrue(scanner.exists(key))
        assertEquals(recentMs, accessStore.lastAccessedAt(key))
    }

    @Test
    fun removalNotifiesOnRemovedSoOfflineBadgesRefresh() = runTest {
        val scanner = scannerWithOneArtifact(lastModifiedAtMs = oldMs)
        val removedKeys = mutableListOf<ContentCacheKey>()
        val cleaner = cleaner(
            autoClear = ContentCacheAutoClear.After30Days,
            accessStore = InMemoryAccessStore().also { it.markAccessedAt(key, oldMs) },
            scanner = scanner,
            onRemoved = { removedKeys += it },
        )

        cleaner.cleanExpired(nowMs)

        assertEquals(listOf(key), removedKeys)
    }

    @Test
    fun anArtifactThatVanishedBetweenScanAndDeleteIsNotCountedAsFreed() = runTest {
        val scanner = FakeScanner(
            listOf(ContentCacheArtifact(key, path = "gone", sizeBytes = 7L, evidenceLastModifiedAtMs = oldMs)),
            present = emptySet(),
        )
        val cleaner = cleaner(
            autoClear = ContentCacheAutoClear.After30Days,
            accessStore = InMemoryAccessStore().also { it.markAccessedAt(key, oldMs) },
            scanner = scanner,
        )

        val result = cleaner.cleanExpired(nowMs)

        assertEquals(ContentCacheCleanResult(scanned = 1, backfilled = 0, removed = 0, freedBytes = 0), result)
    }

    private fun cleaner(
        autoClear: ContentCacheAutoClear,
        accessStore: ContentCacheAccessStore,
        scanner: ContentCacheArtifactScanner,
        onRemoved: suspend (ContentCacheKey) -> Unit = {},
    ): ContentCacheCleaner = ContentCacheCleaner(
        settingsStore = FakeSettingsStore(autoClear),
        accessStore = accessStore,
        artifactScanner = scanner,
        clock = TestClock(nowMs),
        dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher()),
        onRemoved = onRemoved,
    )

    private fun scannerWithOneArtifact(lastModifiedAtMs: Long) = FakeScanner(
        listOf(
            ContentCacheArtifact(
                key = key,
                path = "cache/${key.sourceId}/${key.itemId}.epub",
                sizeBytes = 7L,
                evidenceLastModifiedAtMs = lastModifiedAtMs,
            ),
        ),
    )

    private class FakeScanner(
        private val artifacts: List<ContentCacheArtifact>,
        present: Set<ContentCacheKey> = artifacts.map { it.key }.toSet(),
    ) : ContentCacheArtifactScanner {
        private val live = present.toMutableSet()

        fun exists(key: ContentCacheKey): Boolean = key in live

        override fun listArtifacts(): List<ContentCacheArtifact> = artifacts

        override fun delete(artifact: ContentCacheArtifact): Boolean = live.remove(artifact.key)
    }

    private class FakeSettingsStore(value: ContentCacheAutoClear) : ContentCacheSettingsStore {
        private val flow = MutableStateFlow(value)
        override val autoClear: Flow<ContentCacheAutoClear> = flow
        override suspend fun setAutoClear(value: ContentCacheAutoClear) {
            flow.value = value
        }
    }

    private class InMemoryAccessStore : ContentCacheAccessStore {
        private val entries = mutableMapOf<ContentCacheKey, Long>()

        override suspend fun markAccessed(key: ContentCacheKey) {
            entries[key] = 0L
        }

        override suspend fun markAccessedAt(key: ContentCacheKey, timestampMs: Long) {
            entries[key] = timestampMs
        }

        override suspend fun lastAccessedAt(key: ContentCacheKey): Long? = entries[key]

        override suspend fun lastAccessedAtBulk(keys: Set<ContentCacheKey>): Map<ContentCacheKey, Long?> =
            keys.associateWith { entries[it] }

        override suspend fun forget(key: ContentCacheKey) {
            entries.remove(key)
        }
    }
}

package com.riffle.core.domain

import com.riffle.core.common.Clock
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContentCacheCleanerTest {
    private val nowMs = 1_700_000_000_000L
    private val oldMs = nowMs - 40L * 24L * 60L * 60L * 1000L
    private val key = ContentCacheKey("source", "book/1", ContentCacheArtifactKind.Epub)

    @Test
    fun preExistingArtifactIsBackfilledAndNotDeletedOnFirstCleanup() = runTest {
        val file = tempFile(sizeBytes = 7L, lastModifiedAtMs = oldMs)
        val accessStore = InMemoryAccessStore()
        val cleaner = cleaner(
            autoClear = ContentCacheAutoClear.After30Days,
            accessStore = accessStore,
            artifacts = listOf(artifact(file)),
        )

        val result = cleaner.cleanExpired(nowMs)

        assertEquals(ContentCacheCleanResult(scanned = 1, backfilled = 1, removed = 0, freedBytes = 0), result)
        assertTrue(file.exists())
        assertEquals(oldMs, accessStore.lastAccessedAt(key))
    }

    @Test
    fun backfilledArtifactExpiresOnLaterCleanupWhenStillOld() = runTest {
        val file = tempFile(sizeBytes = 7L, lastModifiedAtMs = oldMs)
        val accessStore = InMemoryAccessStore().also { it.markAccessedAt(key, oldMs) }
        val cleaner = cleaner(
            autoClear = ContentCacheAutoClear.After30Days,
            accessStore = accessStore,
            artifacts = listOf(artifact(file)),
        )

        val result = cleaner.cleanExpired(nowMs)

        assertEquals(ContentCacheCleanResult(scanned = 1, backfilled = 0, removed = 1, freedBytes = 7L), result)
        assertTrue(!file.exists())
        assertEquals(null, accessStore.lastAccessedAt(key))
    }

    @Test
    fun offBackfillsButDoesNotDeleteOldArtifacts() = runTest {
        val file = tempFile(sizeBytes = 7L, lastModifiedAtMs = oldMs)
        val accessStore = InMemoryAccessStore().also { it.markAccessedAt(key, oldMs) }
        val cleaner = cleaner(
            autoClear = ContentCacheAutoClear.Off,
            accessStore = accessStore,
            artifacts = listOf(artifact(file)),
        )

        val result = cleaner.cleanExpired(nowMs)

        assertEquals(ContentCacheCleanResult(scanned = 1, backfilled = 0, removed = 0, freedBytes = 0), result)
        assertTrue(file.exists())
        assertEquals(oldMs, accessStore.lastAccessedAt(key))
    }

    @Test
    fun recentlyAccessedArtifactsAreKept() = runTest {
        val file = tempFile(sizeBytes = 7L, lastModifiedAtMs = oldMs)
        val recentMs = nowMs - 2L * 24L * 60L * 60L * 1000L
        val accessStore = InMemoryAccessStore().also { it.markAccessedAt(key, recentMs) }
        val cleaner = cleaner(
            autoClear = ContentCacheAutoClear.After7Days,
            accessStore = accessStore,
            artifacts = listOf(artifact(file)),
        )

        val result = cleaner.cleanExpired(nowMs)

        assertEquals(ContentCacheCleanResult(scanned = 1, backfilled = 0, removed = 0, freedBytes = 0), result)
        assertTrue(file.exists())
        assertEquals(recentMs, accessStore.lastAccessedAt(key))
    }

    private fun cleaner(
        autoClear: ContentCacheAutoClear,
        accessStore: ContentCacheAccessStore,
        artifacts: List<ContentCacheArtifact>,
    ): ContentCacheCleaner {
        val dispatcher = UnconfinedTestDispatcher()
        return ContentCacheCleaner(
            settingsStore = FakeSettingsStore(autoClear),
            accessStore = accessStore,
            artifactScanner = StaticScanner(artifacts),
            clock = FixedClock(nowMs),
            dispatchers = TestDispatcherProvider(dispatcher),
        )
    }

    private fun artifact(file: File): ContentCacheArtifact =
        ContentCacheArtifact(
            key = key,
            file = file,
            sizeBytes = file.length(),
            evidenceLastModifiedAtMs = file.lastModified(),
        )

    private fun tempFile(sizeBytes: Long, lastModifiedAtMs: Long): File {
        val file = Files.createTempFile("riffle-cache-cleaner", ".epub").toFile()
        file.writeBytes(ByteArray(sizeBytes.toInt()) { 1 })
        assertTrue(file.setLastModified(lastModifiedAtMs))
        file.deleteOnExit()
        return file
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

    private class StaticScanner(
        private val artifacts: List<ContentCacheArtifact>,
    ) : ContentCacheArtifactScanner {
        override fun listArtifacts(): List<ContentCacheArtifact> = artifacts
    }

    private class FixedClock(private val nowMs: Long) : Clock {
        override fun nowMs(): Long = nowMs
        override fun nowNs(): Long = nowMs * 1_000_000L
    }
}

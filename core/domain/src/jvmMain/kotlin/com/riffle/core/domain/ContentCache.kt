package com.riffle.core.domain

import com.riffle.core.common.Clock
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class ContentCacheArtifactKind {
    Epub,
    Pdf,
    Audiobook,
    Cbz,
}

data class ContentCacheKey(
    val sourceId: String,
    val itemId: String,
    val kind: ContentCacheArtifactKind,
)

data class ContentCacheArtifact(
    val key: ContentCacheKey,
    val file: File,
    val sizeBytes: Long,
    val evidenceLastModifiedAtMs: Long?,
)

interface ContentCacheAccessStore {
    suspend fun markAccessed(key: ContentCacheKey)
    suspend fun markAccessedAt(key: ContentCacheKey, timestampMs: Long)
    suspend fun lastAccessedAt(key: ContentCacheKey): Long?
    suspend fun forget(key: ContentCacheKey)
}

interface ContentCacheArtifactScanner {
    fun listArtifacts(): List<ContentCacheArtifact>
}

data class ContentCacheCleanResult(
    val scanned: Int,
    val backfilled: Int,
    val removed: Int,
    val freedBytes: Long,
)

class ContentCacheCleaner(
    private val settingsStore: ContentCacheSettingsStore,
    private val accessStore: ContentCacheAccessStore,
    private val artifactScanner: ContentCacheArtifactScanner,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val onRemoved: suspend (ContentCacheKey) -> Unit = {},
) {
    suspend fun cleanExpired(): ContentCacheCleanResult = cleanExpired(clock.nowMs())

    suspend fun cleanExpired(nowMs: Long): ContentCacheCleanResult = withContext(dispatchers.io) {
        val autoClear = settingsStore.autoClearValue()
        val cutoffMs = autoClear.days?.let { nowMs - it.toLong() * MILLIS_PER_DAY }
        var scanned = 0
        var backfilled = 0
        var removed = 0
        var freedBytes = 0L

        artifactScanner.listArtifacts().forEach { artifact ->
            scanned += 1
            val lastAccessedAt = accessStore.lastAccessedAt(artifact.key)
            if (lastAccessedAt == null) {
                val initialTimestamp = artifact.evidenceLastModifiedAtMs?.takeIf { it > 0L } ?: nowMs
                accessStore.markAccessedAt(artifact.key, initialTimestamp)
                backfilled += 1
                return@forEach
            }
            if (cutoffMs != null && lastAccessedAt <= cutoffMs && artifact.file.exists()) {
                val bytes = artifact.sizeBytes
                if (artifact.file.deleteRecursively()) {
                    accessStore.forget(artifact.key)
                    onRemoved(artifact.key)
                    removed += 1
                    freedBytes += bytes
                }
            }
        }

        ContentCacheCleanResult(
            scanned = scanned,
            backfilled = backfilled,
            removed = removed,
            freedBytes = freedBytes,
        )
    }
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

private suspend fun ContentCacheSettingsStore.autoClearValue(): ContentCacheAutoClear {
    return autoClear.first()
}

package com.riffle.core.domain

import com.riffle.core.common.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// ContentCacheArtifactKind, ContentCacheKey, and ContentCacheAccessStore are in ContentCacheAccessStore.kt.

/**
 * One cached artifact on disk, as the cleaner sees it.
 *
 * [path] is an opaque platform file-system locator — the cleaner never parses it and never opens
 * it; it hands it straight back to [ContentCacheArtifactScanner.delete]. That single seam is the
 * only thing that was pinning this class to the JVM (it used to hold a `java.io.File`), which is
 * why "auto-clear cache after N days" silently did nothing on iOS (#1071 §13).
 */
data class ContentCacheArtifact(
    val key: ContentCacheKey,
    val path: String,
    val sizeBytes: Long,
    val evidenceLastModifiedAtMs: Long?,
)

interface ContentCacheArtifactScanner {
    fun listArtifacts(): List<ContentCacheArtifact>

    /**
     * Deletes the artifact's backing file(s).
     *
     * Returns `true` only when something was actually removed — an artifact that has already
     * vanished since [listArtifacts] must return `false` so the cleaner does not count it as
     * freed space or forget a timestamp that still describes a live file.
     */
    fun delete(artifact: ContentCacheArtifact): Boolean
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
        val cutoffMs = settingsStore.autoClear.first().days?.let { nowMs - it.toLong() * MILLIS_PER_DAY }
        var scanned = 0
        var backfilled = 0
        var removed = 0
        var freedBytes = 0L

        val artifacts = artifactScanner.listArtifacts()
        val timestamps = accessStore.lastAccessedAtBulk(artifacts.map { it.key }.toSet())

        artifacts.forEach { artifact ->
            scanned += 1
            val lastAccessedAt = timestamps[artifact.key]
            if (lastAccessedAt == null) {
                val initialTimestamp = artifact.evidenceLastModifiedAtMs?.takeIf { it > 0L } ?: nowMs
                accessStore.markAccessedAt(artifact.key, initialTimestamp)
                backfilled += 1
                return@forEach
            }
            if (cutoffMs != null && lastAccessedAt <= cutoffMs) {
                val bytes = artifact.sizeBytes
                if (artifactScanner.delete(artifact)) {
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

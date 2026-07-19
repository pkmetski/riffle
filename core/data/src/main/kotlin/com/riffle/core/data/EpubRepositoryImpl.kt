package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.models.LibraryItem
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EpubRepositoryImpl(
    private val catalogRegistry: CatalogRegistry,
    private val cacheStore: LocalStore,
    private val downloadsStore: LocalStore,
    private val positionStore: ReadingPositionStore,
    private val sourceRepository: SourceRepository,
) : EpubRepository {

    /**
     * Single-flight guard: at most one network fetch may be in flight per (sourceId, itemId).
     * Concurrent openEpub/downloadEpub for the same item (e.g. detail-page TOC extraction racing
     * a user's Download tap) previously each fired their own HTTP request; Chitanka's Cloudflare
     * layer rate-limits per source IP and 429s the second one. The mutex serialises the network
     * step; the loser checks cache + downloads on entry and returns the already-fetched bytes.
     */
    private val fetchLocks = mutableMapOf<Pair<String, String>, Mutex>()
    private val fetchLocksGuard = Mutex()

    private suspend fun lockFor(sourceId: String, itemId: String): Mutex = fetchLocksGuard.withLock {
        fetchLocks.getOrPut(sourceId to itemId) { Mutex() }
    }

    override suspend fun openEpub(item: LibraryItem): EpubOpenResult {
        // Resolve the item's OWN Source, not the active one. A user-switch on the same URL mints a
        // fresh source row (SourceRepositoryImpl.commit → UUID.randomUUID), leaving the previous
        // row's cached files under cacheDir/epubs/<oldId>/ in place. Keying by activeSource here
        // made the opener look under the new row's dir, miss the truly-cached file, and fall into
        // the network branch — surfacing "unable to resolve host" when offline even though the
        // library detail badge (which correctly keys by item.sourceId) said the book was cached.
        val local = downloadsStore.get(item.sourceId, item.id) ?: cacheStore.get(item.sourceId, item.id)
        val epubFile = if (local != null) {
            local
        } else {
            val catalog = catalogRegistry.forSourceId(item.sourceId)
                ?: return EpubOpenResult.NetworkError(IllegalStateException("No catalog for item"))
            lockFor(item.sourceId, item.id).withLock {
                // Re-check after acquiring: a concurrent openEpub/downloadEpub may have populated
                // either store while we were waiting for the lock. Reuse those bytes instead of
                // firing a second HTTP request (Chitanka 429s concurrent fetches for the same IP).
                downloadsStore.get(item.sourceId, item.id)
                    ?: cacheStore.get(item.sourceId, item.id)
                    ?: try {
                        catalog.openFile(item.id, BookFormat.Epub, handleHint = item.ebookFileIno).use { stream ->
                            cacheStore.save(item.sourceId, item.id, stream.byteStream())
                        }
                    } catch (t: Throwable) {
                        return EpubOpenResult.NetworkError(t)
                    }
            }
        }
        // Position load intentionally stays keyed by activeSource.id — [saveReadingPosition] also
        // uses it, so this pair stays consistent within a session. Round-trip across a user-switch
        // is a separate concern tracked apart from this fix.
        val activeSource = sourceRepository.getActive()
        val lastPosition = activeSource?.let { positionStore.load(it.id, item.id) }
        return EpubOpenResult.Success(epubFile = epubFile, lastPosition = lastPosition)
    }

    override suspend fun downloadEpub(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): EpubDownloadResult {
        if (downloadsStore.get(item.sourceId, item.id) != null) return EpubDownloadResult.AlreadyDownloaded
        val cached = cacheStore.get(item.sourceId, item.id)
        if (cached != null) return promoteCacheToDownloads(item, cached, onProgress)
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return EpubDownloadResult.NetworkError(IllegalStateException("No catalog for item"))
        return lockFor(item.sourceId, item.id).withLock {
            // Post-lock re-check: while we waited, a concurrent openEpub may have cached the bytes.
            // Reuse them instead of firing a duplicate HTTP request.
            downloadsStore.get(item.sourceId, item.id)?.let { return@withLock EpubDownloadResult.AlreadyDownloaded }
            cacheStore.get(item.sourceId, item.id)?.let { return@withLock promoteCacheToDownloads(item, it, onProgress) }
            try {
                catalog.openFile(item.id, BookFormat.Epub, handleHint = item.ebookFileIno).use { stream ->
                    val progressStream = ProgressReportingInputStream(stream.byteStream(), stream.contentLength, onProgress)
                    downloadsStore.save(item.sourceId, item.id, progressStream)
                }
                EpubDownloadResult.Success
            } catch (t: Throwable) {
                EpubDownloadResult.NetworkError(t)
            }
        }
    }

    private suspend fun promoteCacheToDownloads(
        item: LibraryItem,
        cached: java.io.File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): EpubDownloadResult {
        val size = cached.length()
        cached.inputStream().use {
            downloadsStore.save(item.sourceId, item.id, ProgressReportingInputStream(it, size, onProgress))
        }
        cacheStore.delete(item.sourceId, item.id)
        return EpubDownloadResult.Success
    }

    override suspend fun removeDownload(sourceId: String, itemId: String) {
        downloadsStore.delete(sourceId, itemId)
    }

    override fun isDownloaded(sourceId: String, itemId: String): Boolean = downloadsStore.get(sourceId, itemId) != null

    override fun isCached(sourceId: String, itemId: String): Boolean = cacheStore.get(sourceId, itemId) != null

    override suspend fun saveReadingPosition(itemId: String, cfi: String) {
        val sourceId = sourceRepository.getActive()?.id ?: return
        positionStore.save(sourceId, itemId, cfi)
    }

    override suspend fun loadLastPositionHref(sourceId: String, itemId: String): String? {
        val json = positionStore.load(sourceId, itemId) ?: return null
        return runCatching { org.json.JSONObject(json).getString("href").trimStart('/') }.getOrNull()
    }
}

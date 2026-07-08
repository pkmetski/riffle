package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository

class EpubRepositoryImpl(
    private val catalogRegistry: CatalogRegistry,
    private val cacheStore: LocalStore,
    private val downloadsStore: LocalStore,
    private val positionStore: ReadingPositionStore,
    private val sourceRepository: SourceRepository,
) : EpubRepository {

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
            try {
                catalog.openFile(item.id, BookFormat.Epub, handleHint = item.ebookFileIno).use { stream ->
                    cacheStore.save(item.sourceId, item.id, stream.byteStream())
                }
            } catch (t: Throwable) {
                return EpubOpenResult.NetworkError(t)
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
        if (cached != null) {
            val size = cached.length()
            cached.inputStream().use {
                downloadsStore.save(item.sourceId, item.id, ProgressReportingInputStream(it, size, onProgress))
            }
            cacheStore.delete(item.sourceId, item.id)
            return EpubDownloadResult.Success
        }
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return EpubDownloadResult.NetworkError(IllegalStateException("No catalog for item"))
        return try {
            catalog.openFile(item.id, BookFormat.Epub, handleHint = item.ebookFileIno).use { stream ->
                val progressStream = ProgressReportingInputStream(stream.byteStream(), stream.contentLength, onProgress)
                downloadsStore.save(item.sourceId, item.id, progressStream)
            }
            EpubDownloadResult.Success
        } catch (t: Throwable) {
            EpubDownloadResult.NetworkError(t)
        }
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

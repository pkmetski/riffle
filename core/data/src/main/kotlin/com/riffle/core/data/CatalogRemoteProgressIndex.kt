package com.riffle.core.data

import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.sources.webdav.WebDavProgressEnumerator
import com.riffle.core.sources.webdav.WebDavProgressRemoteFactory
import com.riffle.core.sync.RemoteProgressIndex
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * [RemoteProgressIndex] for WebDAV-backed web sources (ADR 0063).
 *
 * On each sweep it PROPFINDs the WebDAV share via [WebDavProgressEnumerator], then maps the
 * filenames' safe itemIds (slash→dot encoded) back to real itemIds by joining against the local
 * DB. This fixes the "clean-row gap": a position synced once then advanced on a second device is
 * pulled back by the reconciler's `!localDirty && serverAdvanced` branch, even though the local
 * row was clean and not in the dirty-row ledger.
 *
 * Resolution joins WebDAV filenames against all known library item IDs (not just position rows),
 * so progress is pulled on a fresh device that has never opened the book. The slash→dot encoding
 * is not reversible in isolation, so items with no matching library row are still skipped.
 *
 * Server sources (ABS, Komga, Storyteller) are never returned by [sourcesWithRemote] — the check
 * `source.type.isWebSource` gates every path.
 */
class CatalogRemoteProgressIndex @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val annotationSyncConfigStore: AnnotationSyncConfigStore,
    private val enumerator: WebDavProgressEnumerator,
    private val readingPositionDao: ReadingPositionDao,
    private val audiobookPositionDao: AudiobookPositionDao,
    private val libraryItemDao: LibraryItemDao,
) : RemoteProgressIndex {

    override suspend fun sourcesWithRemote(): List<String> {
        annotationSyncConfigStore.observe().value ?: return emptyList()
        return sourceRepository.observeAll().first()
            .filter { it.type.isWebSource }
            .map { it.id }
    }

    override suspend fun remoteEbookItems(sourceId: String): List<String> {
        val (config, namespace) = configAndNamespace(sourceId) ?: return emptyList()
        val enumerated = enumerator.enumerate(config, namespace)
        val positionRows = readingPositionDao.allForSource(sourceId)
        val libraryIds = libraryItemDao.observeBySource(sourceId).first().map { it.id }
        val allKnownIds = (positionRows.map { it.itemId } + libraryIds).distinct()
        val fromServer = resolveItemIds(enumerated.ebookSafeIds, allKnownIds)
        val missingFromServer = positionRows
            .filter { it.lastSyncedAt > 0L && !enumerated.ebookSafeIds.contains(it.itemId.replace("/", ".")) }
            .map { it.itemId }
        return (fromServer + missingFromServer).distinct()
    }

    override suspend fun remoteAudioItems(sourceId: String): List<String> {
        val (config, namespace) = configAndNamespace(sourceId) ?: return emptyList()
        val enumerated = enumerator.enumerate(config, namespace)
        val positionRows = audiobookPositionDao.allForSource(sourceId)
        val libraryIds = libraryItemDao.observeBySource(sourceId).first().map { it.id }
        val allKnownIds = (positionRows.map { it.itemId } + libraryIds).distinct()
        val fromServer = resolveItemIds(enumerated.audioSafeIds, allKnownIds)
        val missingFromServer = positionRows
            .filter { it.lastSyncedAt > 0L && !enumerated.audioSafeIds.contains(it.itemId.replace("/", ".")) }
            .map { it.itemId }
        return (fromServer + missingFromServer).distinct()
    }

    private suspend fun configAndNamespace(sourceId: String): Pair<AnnotationSyncConfig, String>? {
        val source = sourceRepository.getById(sourceId) ?: return null
        if (!source.type.isWebSource) return null
        val config = annotationSyncConfigStore.observe().value ?: return null
        val namespace = WebDavProgressRemoteFactory.webDavNamespace(source.type.name.lowercase())
        return config to namespace
    }

    /**
     * For each safe itemId (slash→dot encoded) found on the server, find the matching local itemId.
     *
     * Primary: join against [localItemIds] via `localId.replace("/", ".")`. This handles any
     * source whose IDs contain dots — the join is unambiguous because the original ID is known.
     *
     * Fallback: if no local match exists (book never opened on this device), reverse the encoding
     * directly with `safeId.replace(".", "/")`. Web-source item IDs are URL path segments and
     * never contain literal dots, so the reversal is unambiguous for all current web sources. The
     * resulting item ID is used to upsert a position-only row; the library item is created lazily
     * when the user first taps the book.
     */
    private fun resolveItemIds(safeIds: List<String>, localItemIds: List<String>): List<String> =
        safeIds.map { safeId ->
            localItemIds.find { it.replace("/", ".") == safeId } ?: safeId.replace(".", "/")
        }
}

package com.riffle.core.data

import com.riffle.core.database.AudiobookPositionDao
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
 * Items found on WebDAV that have no local row at all cannot be safely created here — the
 * slash-to-dot encoding is not reversible for arbitrary source types — so they are silently
 * skipped. Those books will sync on first open on this device (the open-book path triggers its
 * own reconcile).
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
        val localIds = readingPositionDao.allForSource(sourceId).map { it.itemId }
        return resolveItemIds(enumerated.ebookSafeIds, localIds)
    }

    override suspend fun remoteAudioItems(sourceId: String): List<String> {
        val (config, namespace) = configAndNamespace(sourceId) ?: return emptyList()
        val enumerated = enumerator.enumerate(config, namespace)
        val localIds = audiobookPositionDao.allForSource(sourceId).map { it.itemId }
        return resolveItemIds(enumerated.audioSafeIds, localIds)
    }

    private suspend fun configAndNamespace(sourceId: String): Pair<AnnotationSyncConfig, String>? {
        val source = sourceRepository.getById(sourceId) ?: return null
        if (!source.type.isWebSource) return null
        val config = annotationSyncConfigStore.observe().value ?: return null
        val namespace = WebDavProgressRemoteFactory.webDavNamespace(source.type.name.lowercase(), config.username)
        return config to namespace
    }

    /**
     * For each safe itemId (slash→dot encoded) found on the server, find the matching local itemId
     * by computing `localId.replace("/", ".")` and comparing. Returns only items that have a
     * local row — items only on the server (never opened locally) are skipped because the encoding
     * is not safely reversible for arbitrary source types.
     */
    private fun resolveItemIds(safeIds: List<String>, localItemIds: List<String>): List<String> =
        safeIds.mapNotNull { safeId -> localItemIds.find { it.replace("/", ".") == safeId } }
}

package com.riffle.core.data

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.common.Clock
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.SourceRepository
import com.riffle.core.sources.webdav.WebDavProgressRemoteFactory
import com.riffle.core.sync.ProgressRemoteFactory

/**
 * iOS [ProgressRemoteFactory]: covers the catalog-peer path (ABS, Komga, Storyteller) and the
 * WebDAV web-source fallback branch (Chitanka/Gutenberg progress sync via ADR 0063), mirroring
 * [CatalogProgressRemoteFactory] on Android.
 */
internal class IosCatalogProgressRemoteFactory(
    private val catalogRegistry: CatalogRegistry,
    private val libraryItemDao: LibraryItemDao,
    private val translatorFactory: EbookCfiTranslatorFactory,
    private val clock: Clock,
    private val annotationSyncConfigStore: AnnotationSyncConfigStore,
    private val webDavProgressRemoteFactory: WebDavProgressRemoteFactory,
    private val sourceRepository: SourceRepository,
    private val readingPositionDao: ReadingPositionDao,
    private val audiobookPositionDao: AudiobookPositionDao,
) : ProgressRemoteFactory {

    override suspend fun ebook(sourceId: String, itemId: String): ProgressRemote<String>? {
        val peer = catalogRegistry.forSourceId(sourceId) as? ProgressPeerCapability
        if (peer != null) {
            return CatalogEbookProgressRemote(
                peer = peer,
                itemId = itemId,
                translator = translatorFactory.forItem(sourceId, itemId),
                readingProgress = { libraryItemDao.getById(sourceId, itemId)?.readingProgress ?: 0f },
                clock = clock,
            )
        }
        // ADR 0063: Web Sources have no ProgressPeerCapability; fall back to WebDAV file sync.
        val source = sourceRepository.getById(sourceId) ?: return null
        if (!source.type.isWebSource) return null
        val webDavConfig = annotationSyncConfigStore.observe().value ?: return null
        val ns = WebDavProgressRemoteFactory.webDavNamespace(source.type.name.lowercase())
        return webDavProgressRemoteFactory.create(
            config = webDavConfig,
            namespace = ns,
            itemId = itemId,
            readingProgress = { libraryItemDao.getById(sourceId, itemId)?.readingProgress ?: 0f },
            finishedAt = { libraryItemDao.getById(sourceId, itemId)?.finishedAt },
            clock = clock,
            deleted = { readingPositionDao.getByItemId(sourceId, itemId)?.deleted == true },
        )
    }

    override suspend fun audio(sourceId: String, itemId: String): ProgressRemote<Double>? {
        val peer = catalogRegistry.forSourceId(sourceId) as? AudiobookProgressPeerCapability
        if (peer != null) {
            return CatalogAudioProgressRemote(
                peer = peer,
                itemId = itemId,
                duration = { libraryItemDao.getById(sourceId, itemId)?.audioDurationSec ?: 0.0 },
                clock = clock,
            )
        }
        // ADR 0063: Chitanka serves audiobooks via gramofonche.chitanka.info under the same source
        // type. These have no AudiobookProgressPeerCapability; sync via WebDAV file, same namespace
        // as ebook, position stored as a decimal string (seconds).
        val source = sourceRepository.getById(sourceId) ?: return null
        if (!source.type.isWebSource) return null
        val webDavConfig = annotationSyncConfigStore.observe().value ?: return null
        return webDavProgressRemoteFactory.createForAudio(
            config = webDavConfig,
            namespace = WebDavProgressRemoteFactory.webDavNamespace(source.type.name.lowercase()),
            itemId = itemId,
            readingProgress = { libraryItemDao.getById(sourceId, itemId)?.readingProgress ?: 0f },
            finishedAt = { libraryItemDao.getById(sourceId, itemId)?.finishedAt },
            clock = clock,
            deleted = { audiobookPositionDao.getByItemId(sourceId, itemId)?.deleted == true },
        )
    }
}

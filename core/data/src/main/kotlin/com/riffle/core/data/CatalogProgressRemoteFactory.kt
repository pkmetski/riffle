package com.riffle.core.data

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.common.Clock
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.SourceRepository
import com.riffle.core.sources.webdav.WebDavProgressRemoteFactory
import com.riffle.core.sync.ProgressRemoteFactory
import javax.inject.Inject

/**
 * Builds the [ProgressRemote]s the sweep consumes, resolving each Source's Catalog through
 * [CatalogRegistry] and demanding [ProgressPeerCapability] (ebook) or
 * [AudiobookProgressPeerCapability] (audio) before returning a remote. Sources without a
 * capability (LocalFiles, Komga on the audio path — #528) are non-syncable — the factory returns
 * null and the sweep drops the (source, item) pair.
 *
 * For ebook items, [translatorFactory] produces a per-item CFI↔Locator converter (ADR 0013): the
 * converter translates ABS's `epubcfi(...)` to Readium Locator JSON on GET and back on PATCH, so
 * the local store always holds canonical Locator JSON. When the EPUB isn't cached the factory
 * returns null and the remote defers (leaves the row dirty) rather than writing a raw CFI. Peers
 * with [com.riffle.core.catalog.CfiDialect.PAGE_NUMBER] or
 * [com.riffle.core.catalog.CfiDialect.READIUM_NATIVE] bypass the translator entirely.
 *
 * ADR 0063: when a source has no [ProgressPeerCapability] but its [SourceType.isWebSource] is
 * true and WebDAV is configured, a [com.riffle.core.sources.webdav.WebDavProgressRemote] is
 * returned instead. The namespace is `{sourceType}_{webDavUsername}` (e.g. `chitanka_pkmetski`),
 * mirroring the `komga_{userId}` convention in annotation sync so two users who share a WebDAV
 * root don't collide on the same Chitanka/Gutenberg book. Gramofonche audiobooks are served
 * under the CHITANKA source type — the audio branch also checks isWebSource and uses a Double
 * adapter ([WebDavProgressRemoteFactory.createForAudio]) backed by the same file.
 */
class CatalogProgressRemoteFactory @Inject constructor(
    private val catalogRegistry: CatalogRegistry,
    private val libraryItemDao: LibraryItemDao,
    private val translatorFactory: EbookCfiTranslatorFactory,
    private val clock: Clock,
    private val annotationSyncConfigStore: AnnotationSyncConfigStore,
    private val webDavProgressRemoteFactory: WebDavProgressRemoteFactory,
    private val sourceRepository: SourceRepository,
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
        return webDavProgressRemoteFactory.create(
            config = webDavConfig,
            namespace = WebDavProgressRemoteFactory.webDavNamespace(source.type.name.lowercase(), webDavConfig.username),
            itemId = itemId,
            readingProgress = { libraryItemDao.getById(sourceId, itemId)?.readingProgress ?: 0f },
            finishedAt = { libraryItemDao.getById(sourceId, itemId)?.finishedAt },
            clock = clock,
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
            namespace = WebDavProgressRemoteFactory.webDavNamespace(source.type.name.lowercase(), webDavConfig.username),
            itemId = itemId,
            readingProgress = { libraryItemDao.getById(sourceId, itemId)?.readingProgress ?: 0f },
            finishedAt = { libraryItemDao.getById(sourceId, itemId)?.finishedAt },
            clock = clock,
        )
    }
}

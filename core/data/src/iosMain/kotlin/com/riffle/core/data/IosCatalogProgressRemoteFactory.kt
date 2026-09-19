package com.riffle.core.data

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.common.Clock
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.sync.ProgressRemoteFactory

/**
 * iOS [ProgressRemoteFactory]: covers the catalog-peer path (ABS, Komga, Storyteller) that
 * [CatalogProgressRemoteFactory] (Android) also serves. The WebDAV web-source fallback branch
 * (Chitanka/Gutenberg progress sync) is not ported — [WebDavProgressRemoteFactory] lives in
 * `core:sources`' `jvmMain` source set and has no iOS counterpart yet. That gap only affects
 * progress sync for web sources; peer-capable sources (the common case) are unaffected.
 */
internal class IosCatalogProgressRemoteFactory(
    private val catalogRegistry: CatalogRegistry,
    private val libraryItemDao: LibraryItemDao,
    private val translatorFactory: EbookCfiTranslatorFactory,
    private val clock: Clock,
) : ProgressRemoteFactory {

    override suspend fun ebook(sourceId: String, itemId: String): ProgressRemote<String>? {
        val peer = catalogRegistry.forSourceId(sourceId) as? ProgressPeerCapability ?: return null
        return CatalogEbookProgressRemote(
            peer = peer,
            itemId = itemId,
            translator = translatorFactory.forItem(sourceId, itemId),
            readingProgress = { libraryItemDao.getById(sourceId, itemId)?.readingProgress ?: 0f },
            clock = clock,
        )
    }

    override suspend fun audio(sourceId: String, itemId: String): ProgressRemote<Double>? {
        val peer = catalogRegistry.forSourceId(sourceId) as? AudiobookProgressPeerCapability ?: return null
        return CatalogAudioProgressRemote(
            peer = peer,
            itemId = itemId,
            duration = { libraryItemDao.getById(sourceId, itemId)?.audioDurationSec ?: 0.0 },
            clock = clock,
        )
    }
}

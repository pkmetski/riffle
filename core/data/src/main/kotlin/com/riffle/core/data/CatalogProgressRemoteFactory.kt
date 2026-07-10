package com.riffle.core.data

import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.Clock
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.ProgressRemote
import javax.inject.Inject

/**
 * Builds the [ProgressRemote]s the sweep consumes, resolving each Source's Catalog through
 * [CatalogRegistry] and demanding [ProgressPeerCapability] before returning a remote. Sources
 * without a capability (LocalFiles today) are non-syncable — the factory returns null and the
 * sweep drops the (source, item) pair.
 *
 * For ebook items, [translatorFactory] produces a per-item CFI↔Locator converter (ADR 0013): the
 * converter translates ABS's `epubcfi(...)` to Readium Locator JSON on GET and back on PATCH, so
 * the local store always holds canonical Locator JSON. When the EPUB isn't cached the factory
 * returns null and the remote defers (leaves the row dirty) rather than writing a raw CFI.
 */
class CatalogProgressRemoteFactory @Inject constructor(
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
        val peer = catalogRegistry.forSourceId(sourceId) as? ProgressPeerCapability ?: return null
        return CatalogAudioProgressRemote(
            peer = peer,
            itemId = itemId,
            duration = { libraryItemDao.getById(sourceId, itemId)?.audioDurationSec ?: 0.0 },
            clock = clock,
        )
    }
}

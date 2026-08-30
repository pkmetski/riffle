package com.riffle.core.data

import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.data.websource.WebSourceLibraryItemUpserter
import com.riffle.core.database.AudiobookPositionDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.ReadingPositionDao
import com.riffle.core.domain.SourceRepository
import com.riffle.core.sync.PostSweepMaterializer
import com.riffle.core.sync.ProgressRemoteFactory
import kotlinx.coroutines.flow.first

/**
 * [PostSweepMaterializer] that creates `library_items` rows for web-source books whose progress
 * arrived via WebDAV sync but have never been opened on this device.
 *
 * Without this, a synced position row has nowhere to surface in the library grid — `library_items`
 * is only populated when the user taps a book in the browse view
 * ([com.riffle.core.data.websource.WebSourceLibraryItemUpserter]). This materializer fills the
 * gap: after each sweep it scans every web-source position row (both `reading_positions` for ebook
 * and `audiobook_positions` for Gramofonche audio), identifies those lacking a corresponding
 * `library_items` row, fetches the item metadata from the catalog, and upserts the library item
 * with the correct `readingProgress` fraction drawn from the WebDAV remote. The catalog fetch is
 * skipped on error so a transient network failure does not abort the sweep; missing items are
 * retried on the next sweep.
 */
class WebSourceLibraryItemMaterializer constructor(
    private val readingPositionDao: ReadingPositionDao,
    private val audiobookPositionDao: AudiobookPositionDao,
    private val libraryItemDao: LibraryItemDao,
    private val sourceRepository: SourceRepository,
    private val catalogRegistry: CatalogRegistry,
    private val remoteFactory: ProgressRemoteFactory,
    private val upserter: WebSourceLibraryItemUpserter,
) : PostSweepMaterializer {

    override suspend fun run(sourceId: String) {
        val source = sourceRepository.getById(sourceId) ?: return
        if (!source.type.isWebSource) return

        val libraryIds = libraryItemDao.observeBySource(sourceId).first().map { it.id }.toSet()

        val ebookIds = readingPositionDao.allForSource(sourceId).map { it.itemId }.toSet()
        val missingEbook = ebookIds - libraryIds

        val audioIds = audiobookPositionDao.allForSource(sourceId).map { it.itemId }.toSet()
        val missingAudio = audioIds - libraryIds

        val missing = missingEbook + missingAudio
        if (missing.isEmpty()) return

        val catalog = catalogRegistry.forSourceId(sourceId) ?: return

        for (itemId in missingEbook) {
            runCatching {
                val item = catalog.getItem(itemId) ?: return@runCatching
                upserter.upsert(sourceId, item)
                val readingProgress = remoteFactory.ebook(sourceId, itemId)?.get()?.readingProgress
                    ?: return@runCatching
                libraryItemDao.updateReadingProgress(sourceId, itemId, readingProgress)
            }
        }

        for (itemId in missingAudio) {
            runCatching {
                val item = catalog.getItem(itemId) ?: return@runCatching
                upserter.upsert(sourceId, item)
                val readingProgress = remoteFactory.audio(sourceId, itemId)?.get()?.readingProgress
                    ?: return@runCatching
                libraryItemDao.updateReadingProgress(sourceId, itemId, readingProgress)
            }
        }
    }
}

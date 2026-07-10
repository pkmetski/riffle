package com.riffle.core.data.chitanka

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.SourceType
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the "install a Chitanka Source" side of the Add-Source flow. Materialises the
 * singleton Chitanka [SourceEntity] and its two Library rows (Books, Audiobooks) on
 * first use. Zero-config: no credentials, no URL prompt (both domains hardcoded).
 *
 * Idempotent: calling twice returns the existing row and does not duplicate libraries.
 * Only one Chitanka Source can exist per device — the two upstream sites are shared
 * public catalogues where multiple instances add nothing and duplicate library rows.
 */
@Singleton
class ChitankaSourceInstaller @Inject constructor(
    private val sourceDao: SourceDao,
    private val libraryDao: LibraryDao,
    private val logger: Logger,
) {

    /**
     * Creates the Chitanka Source row (if missing) and its two Library rows (Books,
     * Audiobooks). Returns the source id — same on every call once installed.
     */
    suspend fun install(): String {
        logger.d(LogChannel.Chitanka) { "chitanka install start" }
        sourceDao.getByType(SourceType.CHITANKA.name)?.let {
            logger.d(LogChannel.Chitanka) { "chitanka already installed: ${it.id}" }
            return it.id
        }
        val id = UUID.randomUUID().toString()
        val entity = SourceEntity(
            id = id,
            // No meaningful URL — both chitanka.info and gramofonche.chitanka.info are
            // hardcoded in ChitankaCatalog. The placeholder parses cleanly through
            // SourceUrl.parse and never becomes a network target itself.
            url = CHITANKA_URL_PLACEHOLDER,
            isActive = false,
            insecureConnectionAllowed = false,
            username = "",
            serverType = "AUDIOBOOKSHELF",
            type = SourceType.CHITANKA.name,
        )
        val inserted = sourceDao.upsertAsFirstIfNoActive(entity)
        libraryDao.upsertAll(
            listOf(
                LibraryEntity(
                    id = ROOT_BOOKS,
                    name = "Chitanka",
                    mediaType = "book",
                    sourceId = inserted.id,
                ),
                LibraryEntity(
                    id = ROOT_AUDIOBOOKS,
                    name = "Gramofonche",
                    mediaType = "audiobook",
                    sourceId = inserted.id,
                ),
            ),
        )
        logger.d(LogChannel.Chitanka) { "chitanka installed id=${inserted.id}" }
        return inserted.id
    }

    companion object {
        const val CHITANKA_URL_PLACEHOLDER: String = "https://chitanka.invalid"
        const val ROOT_BOOKS: String = "books"
        const val ROOT_AUDIOBOOKS: String = "audiobooks"
    }
}

package com.riffle.core.data.gutenberg

import com.riffle.core.catalog.gutenberg.GutenbergCatalog
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
 * Owns the "install a Project Gutenberg Source" side of the Add-Source flow. Materialises the
 * singleton Gutenberg [SourceEntity] and its single Library row (Books) on first use.
 * Zero-config: no credentials, no URL prompt (gutendex.com is hardcoded in [GutenbergCatalog]).
 *
 * Idempotent: calling twice returns the existing row and does not duplicate libraries. Only one
 * Gutenberg Source can exist per device — a shared public catalogue where multiple instances
 * add nothing and only duplicate library rows.
 */
@Singleton
class GutenbergSourceInstaller @Inject constructor(
    private val sourceDao: SourceDao,
    private val libraryDao: LibraryDao,
    private val logger: Logger,
) {

    /**
     * Creates the Gutenberg Source row (if missing) and its Library row. Returns the source id —
     * same on every call once installed.
     */
    suspend fun install(): String {
        logger.d(LogChannel.Gutenberg) { "gutenberg install start" }
        sourceDao.getByType(SourceType.GUTENBERG.name)?.let { existing ->
            logger.d(LogChannel.Gutenberg) { "gutenberg already installed: ${existing.id}" }
            // Self-heal the Library row on every install — idempotent upsert repairs the drawer
            // if the row was removed out-of-band (partial cascade, manual DB edit, migration
            // edge case). Mirrors the same self-healing pass in
            // [com.riffle.core.data.chitanka.ChitankaSourceInstaller].
            libraryDao.upsertAll(defaultLibraries(existing.id))
            return existing.id
        }
        val id = UUID.randomUUID().toString()
        val entity = SourceEntity(
            id = id,
            // No meaningful URL — gutendex.com is hardcoded in GutenbergCatalog. The placeholder
            // parses cleanly through SourceUrl.parse and never becomes a network target itself.
            url = GUTENBERG_URL_PLACEHOLDER,
            isActive = false,
            insecureConnectionAllowed = false,
            username = "",
            // Legacy compatibility column — historically the SourceEntity.serverType field
            // discriminated ABS/Storyteller; sources without a backend server carry the
            // AUDIOBOOKSHELF literal (same as Chitanka/LocalFiles do) so downstream code that
            // reads it doesn't crash on an unknown value.
            serverType = "AUDIOBOOKSHELF",
            type = SourceType.GUTENBERG.name,
        )
        val inserted = sourceDao.upsertAsFirstIfNoActive(entity)
        libraryDao.upsertAll(defaultLibraries(inserted.id))
        logger.d(LogChannel.Gutenberg) { "gutenberg installed id=${inserted.id}" }
        return inserted.id
    }

    private fun defaultLibraries(sourceId: String): List<LibraryEntity> = listOf(
        LibraryEntity(
            id = GutenbergCatalog.ROOT_BOOKS,
            name = "Books",
            mediaType = "book",
            sourceId = sourceId,
        ),
    )

    companion object {
        const val GUTENBERG_URL_PLACEHOLDER: String = "https://gutenberg.invalid"
    }
}

package com.riffle.core.data.websource

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.SourceDao
import com.riffle.core.database.SourceEntity
import com.riffle.core.domain.WebSourceDescriptor
import com.riffle.core.domain.WebSourceRegistry
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the "install a singleton web source" side of the Add-Source flow for every zero-config
 * web source registered in [WebSourceRegistry] — Chitanka, Project Gutenberg, and any future
 * source with `WebSourceDescriptor.isSingleton == true` + [WebSourceDescriptor.urlPlaceholder]
 * populated + [WebSourceDescriptor.defaultLibraries] populated. Materialises the singleton
 * [SourceEntity] and its default Library rows on first use.
 *
 * Idempotent: calling twice returns the existing row and does not duplicate libraries. The
 * default-library upsert on the "already installed" branch self-heals the drawer if a Library
 * row was removed out-of-band (partial cascade, manual DB edit, migration edge case) — same
 * behaviour as the deleted per-source installers had (`ChitankaSourceInstaller`,
 * `GutenbergSourceInstaller`).
 */
@Singleton
class SingletonWebSourceInstaller @Inject constructor(
    private val sourceDao: SourceDao,
    private val libraryDao: LibraryDao,
    private val registry: WebSourceRegistry,
    private val logger: Logger,
) {

    /**
     * Install the singleton source for [type] (looked up via [registry]). Returns the source id —
     * same on every call once installed. Throws if the descriptor is missing install data
     * (`urlPlaceholder` / `defaultLibraries`) — signals a wiring bug, not a runtime error.
     */
    suspend fun install(type: com.riffle.core.models.SourceType): String {
        val descriptor = registry.forTypeOrError(type)
        require(descriptor.isSingleton) {
            "SingletonWebSourceInstaller only installs isSingleton descriptors; got $type"
        }
        val urlPlaceholder = requireNotNull(descriptor.urlPlaceholder) {
            "SingletonWebSourceInstaller requires descriptor.urlPlaceholder for $type"
        }
        require(descriptor.defaultLibraries.isNotEmpty()) {
            "SingletonWebSourceInstaller requires descriptor.defaultLibraries for $type"
        }

        logger.d(LogChannel.WebSourceCache) { "${descriptor.displayName} install start" }
        sourceDao.getByType(type.name)?.let { existing ->
            logger.d(LogChannel.WebSourceCache) {
                "${descriptor.displayName} already installed: ${existing.id}"
            }
            libraryDao.upsertAll(descriptor.libraryEntitiesFor(existing.id))
            return existing.id
        }
        val entity = SourceEntity(
            id = UUID.randomUUID().toString(),
            url = urlPlaceholder,
            isActive = false,
            insecureConnectionAllowed = false,
            username = "",
            // Legacy compatibility column — the SourceEntity.serverType field historically
            // discriminated ABS/Storyteller. Sources without a backend server carry the
            // AUDIOBOOKSHELF literal so downstream code that reads it doesn't crash on an
            // unknown value.
            serverType = "AUDIOBOOKSHELF",
            type = type.name,
        )
        val inserted = sourceDao.upsertAsFirstIfNoActive(entity)
        libraryDao.upsertAll(descriptor.libraryEntitiesFor(inserted.id))
        logger.d(LogChannel.WebSourceCache) {
            "${descriptor.displayName} installed id=${inserted.id}"
        }
        return inserted.id
    }
}

private fun WebSourceDescriptor.libraryEntitiesFor(sourceId: String): List<LibraryEntity> =
    defaultLibraries.map { seed ->
        LibraryEntity(
            id = seed.id,
            name = seed.name,
            mediaType = seed.mediaType,
            sourceId = sourceId,
        )
    }

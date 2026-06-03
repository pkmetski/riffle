package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.NetworkStorytellerBook
import com.riffle.core.network.NetworkStorytellerBooksResult
import com.riffle.core.network.StorytellerLibraryApi
import kotlinx.coroutines.flow.first

/**
 * Builds local [LibraryItemEntity] rows from Storyteller readaloud books. Shared by the
 * active-server refresh ([LibraryRepositoryImpl.refreshStorytellerReadalouds]) and the proactive
 * StorytellerReadaloudSyncer so both produce identical rows (the matcher keys off
 * title/author/isbn/asin). Existing local reading progress and last-opened timestamps are merged
 * back in so a refresh never resets them.
 */
internal fun storytellerBooksToEntities(
    books: List<NetworkStorytellerBook>,
    libraryId: String,
    coverUrlOf: (Long) -> String,
    lastOpenedAtMap: Map<String, Long?>,
    progressMap: Map<String, Float>,
): List<LibraryItemEntity> = books.map { book ->
    val id = book.id.toString()
    LibraryItemEntity(
        id = id,
        libraryId = libraryId,
        title = book.title,
        author = book.authors.joinToString(", "),
        coverUrl = coverUrlOf(book.id),
        // Storyteller has a positions endpoint but reader-side bundle fetch and
        // progress sync come in later slices (#37/#38); for now seed with whatever
        // we've seen locally so the UI stays stable.
        readingProgress = progressMap[id] ?: 0f,
        // Readalouds are always EPUB 3 with media overlays.
        ebookFormat = EbookFormat.Epub.toStorageString(),
        ebookFileIno = null,
        description = null,
        seriesName = null,
        publishedYear = null,
        genres = "",
        publisher = null,
        lastOpenedAt = lastOpenedAtMap[id],
        addedAt = null,
        isbn = book.isbn,
        asin = book.asin,
    )
}

private const val STORYTELLER_SYNC_TTL_MILLIS = 10L * 60L * 1000L

/**
 * Proactively syncs Storyteller readaloud books for all known servers in the background.
 * Open so tests can subclass it with a no-op/spy `syncStale()`.
 */
open class StorytellerReadaloudSyncer(
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val storytellerApi: StorytellerLibraryApi,
    private val libraryItemDao: LibraryItemDao,
    private val clock: () -> Long,
    private val ttlMillis: Long = STORYTELLER_SYNC_TTL_MILLIS,
) {
    private val lastSyncedAt = mutableMapOf<String, Long>()

    /** Best-effort: fetch+store readalouds for each stale Storyteller server. Never throws. */
    open suspend fun syncStale() {
        val servers = runCatching { serverRepository.observeAll().first() }.getOrNull().orEmpty()
            .filter { it.serverType == ServerType.STORYTELLER }
        val now = clock()
        for (server in servers) {
            val last = lastSyncedAt[server.id]
            if (last != null && now - last < ttlMillis) continue
            val token = tokenStorage.getToken(server.id) ?: continue
            val ok = runCatching { fetchAndStore(server, token) }.getOrDefault(false)
            if (ok) lastSyncedAt[server.id] = now
        }
    }

    private suspend fun fetchAndStore(server: Server, token: String): Boolean {
        val libraryId = ServerRepositoryImpl.readaloudLibraryId(server.id)
        return when (val r = storytellerApi.listReadalouds(server.url.value, token, server.insecureConnectionAllowed)) {
            is NetworkStorytellerBooksResult.Success -> {
                val lastOpenedAtMap = libraryItemDao.getLastOpenedAtMap(libraryId).associate { it.id to it.lastOpenedAt }
                val progressMap = libraryItemDao.getReadingProgressMap(libraryId).associate { it.id to it.readingProgress }
                val entities = storytellerBooksToEntities(
                    books = r.books,
                    libraryId = libraryId,
                    coverUrlOf = { bookId -> storytellerApi.coverUrl(server.url.value, bookId) },
                    lastOpenedAtMap = lastOpenedAtMap,
                    progressMap = progressMap,
                )
                libraryItemDao.replaceAllForLibrary(libraryId, entities)
                true
            }
            is NetworkStorytellerBooksResult.NetworkError -> false
        }
    }
}

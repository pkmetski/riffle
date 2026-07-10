package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.Source
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.NetworkStorytellerBook
import com.riffle.core.network.StorytellerLibraryApi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first

/**
 * Builds local [LibraryItemEntity] rows from Storyteller readaloud books. Called by
 * [StorytellerReadaloudSyncer.fetchAndStore] as part of the background catalogue refresh that the
 * [com.riffle.core.domain.usecase.RefreshLibraryItems] use-case dispatches after an ABS refresh.
 * The matcher keys off title/author/isbn/asin. Existing local reading progress and last-opened
 * timestamps are merged back in so a refresh never resets them.
 */
internal fun storytellerBooksToEntities(
    books: List<NetworkStorytellerBook>,
    sourceId: String,
    libraryId: String,
    coverUrlOf: (Long) -> String,
    lastOpenedAtMap: Map<String, Long?>,
    progressMap: Map<String, Float>,
): List<LibraryItemEntity> = books.map { book ->
    val id = book.id.toString()
    LibraryItemEntity(
        sourceId = sourceId,
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
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val storytellerApi: StorytellerLibraryApi,
    private val libraryItemDao: LibraryItemDao,
    private val clock: () -> Long,
    private val ttlMillis: Long = STORYTELLER_SYNC_TTL_MILLIS,
) : com.riffle.core.domain.StorytellerReadaloudCacheSyncer {
    private val lastSyncedAt = ConcurrentHashMap<String, Long>()

    /** Best-effort: fetch+store readalouds for each stale Storyteller service. Never throws. */
    override suspend fun syncStale() {
        val servers = runCatching { sourceRepository.observeAll().first() }.getOrNull().orEmpty()
            .filter { it.serverType == ServerType.STORYTELLER_SERVICE }
        val now = clock()
        for (source in servers) {
            val last = lastSyncedAt[source.id]
            if (last != null && now - last < ttlMillis) continue
            val token = tokenStorage.getToken(source.id) ?: continue
            val ok = runCatching { fetchAndStore(source, token) }.getOrDefault(false)
            if (ok) lastSyncedAt[source.id] = now
        }
    }

    private suspend fun fetchAndStore(source: Source, token: String): Boolean {
        val libraryId = SourceRepositoryImpl.readaloudLibraryId(source.id)
        val r = storytellerApi.listReadalouds(source.url.value, token, source.insecureConnectionAllowed)
        if (r !is NetworkResult.Success) return false
        val lastOpenedAtMap = libraryItemDao.getLastOpenedAtMap(source.id, libraryId).associate { it.id to it.lastOpenedAt }
        val progressMap = libraryItemDao.getReadingProgressMap(source.id, libraryId).associate { it.id to it.readingProgress }
        val entities = storytellerBooksToEntities(
            books = r.value,
            sourceId = source.id,
            libraryId = libraryId,
            coverUrlOf = { bookId -> storytellerApi.coverUrl(source.url.value, bookId) },
            lastOpenedAtMap = lastOpenedAtMap,
            progressMap = progressMap,
        )
        libraryItemDao.replaceAllForLibrary(source.id, libraryId, entities)
        return true
    }
}

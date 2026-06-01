package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.MatchableItemRow
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.domain.MatchResult
import com.riffle.core.domain.MatchableAbsItem
import com.riffle.core.domain.MatchableStorytellerBook
import com.riffle.core.domain.ReadaloudMatcher
import com.riffle.core.domain.ServerType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the Storyteller↔ABS auto-matcher and persists results into [ReadaloudLinkDao].
 *
 * Strategy: for every refresh that could change either side's content (Storyteller library
 * refresh, ABS library refresh, Server add) we re-reconcile the link set globally. Library
 * sizes are bounded and the matcher is cheap; the per-tier incremental optimisation
 * mentioned in ADR 0021 ("only new/changed books") is deferred until we hit a real cost.
 *
 * Sticky behaviour: rows with [ReadaloudLinkEntity.userConfirmed] = true are never touched
 * by the auto-matcher — per ADR 0021 that's reserved for the review-queue UI slice.
 */
@Singleton
class ReadaloudMatchingService @Inject constructor(
    private val libraryItemDao: LibraryItemDao,
    private val readaloudLinkDao: ReadaloudLinkDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun reconcileLinks() {
        val storytellerBooks = libraryItemDao.listMatchableByServerType(ServerType.STORYTELLER.name)
        val absItems = libraryItemDao.listMatchableByServerType(ServerType.AUDIOBOOKSHELF.name)
        if (storytellerBooks.isEmpty() || absItems.isEmpty()) {
            // Nothing to match against on one side — drop any stale auto-links.
            removeStaleAutoLinks(emptySet())
            return
        }

        val candidates = absItems.map { it.toAbsCandidate() }
        val now = clock()
        val freshAutoLinks = mutableSetOf<Pair<String, String>>()

        for (book in storytellerBooks) {
            val existing = readaloudLinkDao.findByStorytellerBook(book.serverId, book.itemId)
            if (existing?.userConfirmed == true) {
                freshAutoLinks += book.serverId to book.itemId
                continue
            }

            when (val result = ReadaloudMatcher.match(book.toStorytellerBook(), candidates)) {
                is MatchResult.Confirmed -> {
                    readaloudLinkDao.upsert(
                        ReadaloudLinkEntity(
                            storytellerServerId = book.serverId,
                            storytellerBookId = book.itemId,
                            absServerId = result.absServerUuid,
                            absLibraryItemId = result.absLibraryItemId,
                            state = ReadaloudLinkEntity.STATE_CONFIRMED,
                            userConfirmed = false,
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now,
                        )
                    )
                    freshAutoLinks += book.serverId to book.itemId
                }
                MatchResult.Unmatched -> {
                    if (existing != null && !existing.userConfirmed) {
                        readaloudLinkDao.deleteByStorytellerBook(book.serverId, book.itemId)
                    }
                }
            }
        }

        removeStaleAutoLinks(freshAutoLinks)
    }

    private suspend fun removeStaleAutoLinks(freshKeys: Set<Pair<String, String>>) {
        // No-op for now: rows for Storyteller books that no longer exist will be
        // FK-cascaded when their Storyteller Server is removed, and a book disappearing
        // from a library refresh is rare and handled by the next reconcile when the user
        // re-adds it. Keeping this hook so future incremental refinements have a seam.
    }

    private fun MatchableItemRow.toStorytellerBook() = MatchableStorytellerBook(
        uuid = "$serverId:$itemId",
        title = title,
        author = author,
        isbn = isbn,
        asin = asin,
    )

    private fun MatchableItemRow.toAbsCandidate() = MatchableAbsItem(
        serverUuid = serverId,
        libraryItemId = itemId,
        title = title,
        author = author,
        isbn = isbn,
        asin = asin,
    )
}

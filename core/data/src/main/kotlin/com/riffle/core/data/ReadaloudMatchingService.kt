package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.MatchableItemRow
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.domain.Confirmed
import com.riffle.core.domain.MatchableAbsItem
import com.riffle.core.domain.MatchableStorytellerBook
import com.riffle.core.domain.ReadaloudMatcher
import com.riffle.core.domain.ServerType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the Storyteller↔ABS auto-matcher and persists results into [ReadaloudLinkDao].
 *
 * Schema is ABS-keyed (one row per ABS Library Item), so a Storyteller readaloud that
 * matches both an ebook entry and an audiobook stub in two different ABS libraries
 * produces two rows. Each ABS slot is independently sticky on [ReadaloudLinkEntity.userConfirmed].
 *
 * The reconciler:
 *  - writes/refreshes one row per (readaloud, ABS candidate) the matcher confirms,
 *  - preserves any pre-existing user-Confirmed row (never overwrites or deletes it),
 *  - sweeps auto-Confirmed rows whose ABS slot no longer survives the current pass.
 */
@Singleton
class ReadaloudMatchingService(
    private val libraryItemDao: LibraryItemDao,
    private val readaloudLinkDao: ReadaloudLinkDao,
    private val clock: () -> Long,
) {
    @Inject constructor(
        libraryItemDao: LibraryItemDao,
        readaloudLinkDao: ReadaloudLinkDao,
    ) : this(libraryItemDao, readaloudLinkDao, System::currentTimeMillis)

    suspend fun reconcileLinks() {
        val storytellerBooks = libraryItemDao.listMatchableByServerType(ServerType.STORYTELLER.name)
        val absItems = libraryItemDao.listMatchableByServerType(ServerType.AUDIOBOOKSHELF.name)

        val candidates = absItems.map { it.toAbsCandidate() }
        val now = clock()
        // Track every ABS PK the current pass auto-Confirmed. Used to sweep stale rows.
        val freshAutoSlots = mutableSetOf<Pair<String, String>>()

        for (book in storytellerBooks) {
            val matches = ReadaloudMatcher.match(book.toStorytellerBook(), candidates)
            for (match in matches) {
                val slot = match.absServerUuid to match.absLibraryItemId
                val existing = readaloudLinkDao.findByAbsItem(match.absServerUuid, match.absLibraryItemId)
                if (existing?.userConfirmed == true) {
                    // Sticky: the user owns this slot. The auto-matcher must not touch it,
                    // even if it'd now point somewhere else. Mark fresh only when the
                    // user-confirmed row already points to the matcher's verdict, so the
                    // sweep below doesn't wipe an unrelated user choice.
                    if (existing.storytellerServerId == book.serverId && existing.storytellerBookId == book.itemId) {
                        freshAutoSlots += slot
                    }
                    continue
                }
                readaloudLinkDao.upsert(
                    ReadaloudLinkEntity(
                        absServerId = match.absServerUuid,
                        absLibraryItemId = match.absLibraryItemId,
                        storytellerServerId = book.serverId,
                        storytellerBookId = book.itemId,
                        state = ReadaloudLinkEntity.STATE_CONFIRMED,
                        userConfirmed = false,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    )
                )
                freshAutoSlots += slot
            }
        }

        sweepStaleAutoLinks(freshAutoSlots)
    }

    /**
     * Delete any auto-Confirmed row whose ABS slot didn't survive the current pass — its
     * matcher verdict has moved or evaporated. User-Confirmed rows are skipped.
     */
    private suspend fun sweepStaleAutoLinks(freshAutoSlots: Set<Pair<String, String>>) {
        val all = readaloudLinkDao.allRows()
        for (row in all) {
            if (row.userConfirmed) continue
            val slot = row.absServerId to row.absLibraryItemId
            if (slot !in freshAutoSlots) {
                readaloudLinkDao.deleteByAbsItem(row.absServerId, row.absLibraryItemId)
            }
        }
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

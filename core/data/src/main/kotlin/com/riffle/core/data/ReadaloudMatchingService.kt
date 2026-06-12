package com.riffle.core.data

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.MatchableItemRow
import com.riffle.core.database.ReadaloudCandidateDao
import com.riffle.core.database.ReadaloudCandidateEntity
import com.riffle.core.database.ReadaloudDismissalDao
import com.riffle.core.database.ReadaloudDismissalEntity
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.domain.MatchOutcome
import com.riffle.core.domain.MatchableAbsItem
import com.riffle.core.domain.MatchableStorytellerBook
import com.riffle.core.domain.ReadaloudMatcher
import com.riffle.core.domain.ServerType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the Storyteller↔ABS auto-matcher ([ReadaloudMatcher]) and persists its verdicts, per
 * the full ADR 0021 ladder:
 *
 *  - **Confirmed** (Tier 1/2) → one [ReadaloudLinkEntity] per ABS slot. Schema is ABS-keyed, so
 *    a readaloud matching both an ebook entry and an audiobook stub produces two rows. Each slot
 *    is independently sticky on [ReadaloudLinkEntity.userConfirmed].
 *  - **Pending Review** (Tier 3) → one [ReadaloudCandidateEntity] per surviving fuzzy candidate.
 *  - **Unmatched** (Tier 4) → nothing persisted.
 *
 * User decisions are sticky and never re-evaluated:
 *  - A readaloud with any user-Confirmed link is left entirely alone (no re-match, no candidates).
 *  - A per-book "No match — don't ask again" ([ReadaloudDismissalEntity.SCOPE_BOOK]) keeps the
 *    book Unmatched — the matcher never proposes candidates for it.
 *  - A per-candidate dismissal ([ReadaloudDismissalEntity.SCOPE_CANDIDATE]) drops that one pair
 *    from Pending Review on every subsequent run.
 */
@Singleton
class ReadaloudMatchingService(
    private val libraryItemDao: LibraryItemDao,
    private val readaloudLinkDao: ReadaloudLinkDao,
    private val readaloudCandidateDao: ReadaloudCandidateDao,
    private val readaloudDismissalDao: ReadaloudDismissalDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(
        libraryItemDao: LibraryItemDao,
        readaloudLinkDao: ReadaloudLinkDao,
        readaloudCandidateDao: ReadaloudCandidateDao,
        readaloudDismissalDao: ReadaloudDismissalDao,
    ) : this(libraryItemDao, readaloudLinkDao, readaloudCandidateDao, readaloudDismissalDao, System::currentTimeMillis)

    suspend fun reconcileLinks() {
        val storytellerBooks = libraryItemDao.listMatchableByServerType(ServerType.STORYTELLER.name)
        val absItems = libraryItemDao.listMatchableByServerType(ServerType.AUDIOBOOKSHELF.name)

        val candidates = absItems.map { it.toAbsCandidate() }
        val now = clock()
        // Track every ABS PK the current pass auto-Confirmed (or a sticky user slot), to sweep
        // stale rows. Fresh Pending-Review candidate rows are collected then written wholesale.
        val freshAutoSlots = mutableSetOf<Pair<String, String>>()
        val freshCandidates = mutableListOf<ReadaloudCandidateEntity>()

        for (book in storytellerBooks) {
            val existingLinks = readaloudLinkDao.findByStorytellerBook(book.serverId, book.itemId)
            // Sticky: a user-Confirmed readaloud is left untouched — no re-match, no candidates.
            // Keep its slots fresh so the stale sweep below doesn't delete them.
            if (existingLinks.any { it.userConfirmed }) {
                existingLinks.filter { it.userConfirmed }
                    .forEach { freshAutoSlots += it.absServerId to it.absLibraryItemId }
                continue
            }
            // Sticky: "No match — don't ask again" keeps the book Unmatched forever.
            if (readaloudDismissalDao.isBookDismissed(book.serverId, book.itemId)) continue

            when (val outcome = ReadaloudMatcher.match(book.toStorytellerBook(), candidates)) {
                is MatchOutcome.Confirmed -> outcome.links.forEach { match ->
                    val slot = match.absServerUuid to match.absLibraryItemId
                    val existing = readaloudLinkDao.findByAbsItem(match.absServerUuid, match.absLibraryItemId)
                    if (existing?.userConfirmed == true) {
                        // The user owns this ABS slot; never overwrite it. Only mark it fresh when
                        // it already points at this book, so an unrelated user choice isn't swept.
                        if (existing.storytellerServerId == book.serverId && existing.storytellerBookId == book.itemId) {
                            freshAutoSlots += slot
                        }
                        return@forEach
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

                is MatchOutcome.PendingReview -> {
                    val dismissedPairs = readaloudDismissalDao
                        .findByStorytellerBook(book.serverId, book.itemId)
                        .filter { it.scope == ReadaloudDismissalEntity.SCOPE_CANDIDATE }
                        .map { it.absServerId to it.absLibraryItemId }
                        .toSet()
                    outcome.candidates
                        .filter { (it.absServerUuid to it.absLibraryItemId) !in dismissedPairs }
                        .forEach {
                            freshCandidates += ReadaloudCandidateEntity(
                                storytellerServerId = book.serverId,
                                storytellerBookId = book.itemId,
                                absServerId = it.absServerUuid,
                                absLibraryItemId = it.absLibraryItemId,
                                score = it.score,
                            )
                        }
                }

                MatchOutcome.Unmatched -> Unit
            }
        }

        sweepStaleAutoLinks(freshAutoSlots)
        // The candidate table is fully regenerated each pass (reconcile sees every book/item).
        readaloudCandidateDao.clearAll()
        if (freshCandidates.isNotEmpty()) readaloudCandidateDao.upsertAll(freshCandidates)
        // No cross-EPUB index builds are enqueued here: looping every Confirmed link on every library
        // refresh was wasteful (it re-checked all matches on each navigation). The build is now triggered
        // at the deterministic moment its prerequisite arrives — readaloud bundle download-complete — and
        // self-healed on reader/player open via ReaderSyncFactory (ADR 0031).
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

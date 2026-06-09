package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.ReadaloudCandidateDao
import com.riffle.core.database.ReadaloudCandidateEntity
import com.riffle.core.database.ReadaloudDismissalDao
import com.riffle.core.database.ReadaloudDismissalEntity
import com.riffle.core.database.ReadaloudLinkDao
import com.riffle.core.database.ReadaloudLinkEntity
import com.riffle.core.domain.AbsCandidate
import com.riffle.core.domain.AbsPickerItem
import com.riffle.core.domain.ConfirmedReadaloud
import com.riffle.core.domain.PendingReadaloud
import com.riffle.core.domain.ReadaloudReview
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.UnmatchedReadaloud
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the auto-matcher's persisted verdicts ([ReadaloudLinkDao], [ReadaloudCandidateDao]) and the
 * user's sticky decisions ([ReadaloudDismissalDao]) to drive the review queue, and applies the
 * per-candidate / per-book actions. ABS titles, authors, library names and covers are resolved
 * lazily from [LibraryItemDao] / [LibraryDao].
 */
@Singleton
class ReadaloudReviewRepositoryImpl(
    private val libraryItemDao: LibraryItemDao,
    private val libraryDao: LibraryDao,
    private val linkDao: ReadaloudLinkDao,
    private val candidateDao: ReadaloudCandidateDao,
    private val dismissalDao: ReadaloudDismissalDao,
    private val clock: () -> Long,
) : ReadaloudReviewRepository {

    @Inject constructor(
        libraryItemDao: LibraryItemDao,
        libraryDao: LibraryDao,
        linkDao: ReadaloudLinkDao,
        candidateDao: ReadaloudCandidateDao,
        dismissalDao: ReadaloudDismissalDao,
    ) : this(libraryItemDao, libraryDao, linkDao, candidateDao, dismissalDao, System::currentTimeMillis)

    override fun observeReview(storytellerServerId: String): Flow<ReadaloudReview> =
        combine(
            linkDao.observeAll(),
            candidateDao.observeForStorytellerServer(storytellerServerId),
        ) { links, candidates ->
            buildReview(storytellerServerId, links, candidates)
        }

    private suspend fun buildReview(
        storytellerServerId: String,
        allLinks: List<ReadaloudLinkEntity>,
        candidates: List<ReadaloudCandidateEntity>,
    ): ReadaloudReview {
        val books = libraryItemDao.listMatchableByServerType(ServerType.STORYTELLER.name)
            .filter { it.serverId == storytellerServerId }

        val linksForServer = allLinks.filter { it.storytellerServerId == storytellerServerId }
        val confirmedBookIds = linksForServer.map { it.storytellerBookId }.toSet()
        val candidatesByBook = candidates.groupBy { it.storytellerBookId }

        val libraryNameCache = mutableMapOf<String, String>()
        // Library ids are unique only within a Server (issue #113) — key the cache and the lookup
        // by (serverId, libraryId) so two Servers' same-id libraries resolve to their own names.
        suspend fun libraryName(serverId: String, libraryId: String): String =
            libraryNameCache.getOrPut("$serverId|$libraryId") {
                libraryDao.getById(serverId, libraryId)?.name ?: libraryId
            }

        // Group per readaloud so Unlink detaches the whole match (ebook + audiobook stub) at once.
        val confirmed = linksForServer
            .groupBy { it.storytellerBookId }
            .mapNotNull { (storytellerBookId, links) ->
                val targets = links.mapNotNull { link ->
                    val abs = libraryItemDao.getById(link.absServerId, link.absLibraryItemId) ?: return@mapNotNull null
                    ConfirmedReadaloud.ConfirmedTarget(
                        absServerId = link.absServerId,
                        absLibraryItemId = link.absLibraryItemId,
                        absTitle = abs.title,
                        absLibraryName = libraryName(abs.serverId, abs.libraryId),
                    )
                }
                if (targets.isEmpty()) return@mapNotNull null
                val book = libraryItemDao.getById(storytellerServerId, storytellerBookId)
                ConfirmedReadaloud(
                    storytellerServerId = storytellerServerId,
                    storytellerBookId = storytellerBookId,
                    title = book?.title ?: storytellerBookId,
                    targets = targets.sortedBy { it.absLibraryName.lowercase() },
                )
            }
            .sortedBy { it.title.lowercase() }

        val pending = books
            .filter { it.itemId !in confirmedBookIds && candidatesByBook.containsKey(it.itemId) }
            .map { book ->
                val bookEntity = libraryItemDao.getById(storytellerServerId, book.itemId)
                val cands = candidatesByBook.getValue(book.itemId)
                    .sortedByDescending { it.score }
                    .mapNotNull { cand ->
                        val abs = libraryItemDao.getById(cand.absServerId, cand.absLibraryItemId) ?: return@mapNotNull null
                        AbsCandidate(
                            absServerId = cand.absServerId,
                            absLibraryItemId = cand.absLibraryItemId,
                            absTitle = abs.title,
                            absAuthor = abs.author,
                            absLibraryName = libraryName(abs.serverId, abs.libraryId),
                            coverUrl = abs.coverUrl,
                            score = cand.score,
                        )
                    }
                PendingReadaloud(
                    storytellerServerId = storytellerServerId,
                    storytellerBookId = book.itemId,
                    title = book.title,
                    author = book.author,
                    coverUrl = bookEntity?.coverUrl,
                    candidates = cands,
                )
            }
            .filter { it.candidates.isNotEmpty() }
            .sortedBy { it.title.lowercase() }

        val pendingBookIds = pending.map { it.storytellerBookId }.toSet()

        val unmatched = books
            .filter { it.itemId !in confirmedBookIds && it.itemId !in pendingBookIds }
            .map { book ->
                val bookEntity = libraryItemDao.getById(storytellerServerId, book.itemId)
                UnmatchedReadaloud(
                    storytellerServerId = storytellerServerId,
                    storytellerBookId = book.itemId,
                    title = book.title,
                    author = book.author,
                    coverUrl = bookEntity?.coverUrl,
                )
            }
            .sortedBy { it.title.lowercase() }

        return ReadaloudReview(pending = pending, unmatched = unmatched, confirmed = confirmed)
    }

    override suspend fun confirmCandidate(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    ) {
        createUserConfirmedLink(storytellerServerId, storytellerBookId, absServerId, absLibraryItemId)
        // The book is now Confirmed; drop all of its Pending-Review candidates.
        candidateDao.deleteByStorytellerBook(storytellerServerId, storytellerBookId)
    }

    override suspend fun dismissCandidate(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    ) {
        dismissalDao.upsert(
            ReadaloudDismissalEntity(
                storytellerServerId = storytellerServerId,
                storytellerBookId = storytellerBookId,
                scope = ReadaloudDismissalEntity.SCOPE_CANDIDATE,
                absServerId = absServerId,
                absLibraryItemId = absLibraryItemId,
            )
        )
        candidateDao.deleteCandidate(storytellerServerId, storytellerBookId, absServerId, absLibraryItemId)
    }

    override suspend fun dismissBook(storytellerServerId: String, storytellerBookId: String) {
        dismissalDao.upsert(
            ReadaloudDismissalEntity(
                storytellerServerId = storytellerServerId,
                storytellerBookId = storytellerBookId,
                scope = ReadaloudDismissalEntity.SCOPE_BOOK,
            )
        )
        candidateDao.deleteByStorytellerBook(storytellerServerId, storytellerBookId)
    }

    override suspend fun unlinkBook(storytellerServerId: String, storytellerBookId: String) {
        linkDao.deleteByStorytellerBook(storytellerServerId, storytellerBookId)
    }

    override suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String) {
        linkDao.deleteByAbsItem(absServerId, absLibraryItemId)
    }

    override suspend fun pairManually(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    ) {
        createUserConfirmedLink(storytellerServerId, storytellerBookId, absServerId, absLibraryItemId)
        // Manual pairing overrides any prior "don't ask again" and clears stale candidates.
        dismissalDao.clearBookDismissal(storytellerServerId, storytellerBookId)
        candidateDao.deleteByStorytellerBook(storytellerServerId, storytellerBookId)
    }

    override suspend fun searchAbsItems(query: String): List<AbsPickerItem> {
        val trimmed = query.trim()
        val all = libraryItemDao.listMatchableByServerType(ServerType.AUDIOBOOKSHELF.name)
        val matched = if (trimmed.isEmpty()) {
            all
        } else {
            all.filter { it.title.contains(trimmed, ignoreCase = true) || it.author.contains(trimmed, ignoreCase = true) }
        }
        val libraryNameCache = mutableMapOf<String, String>()
        return matched
            .sortedBy { it.title.lowercase() }
            .mapNotNull { row ->
                val abs: LibraryItemEntity = libraryItemDao.getById(row.serverId, row.itemId) ?: return@mapNotNull null
                val name = libraryNameCache.getOrPut("${abs.serverId}|${abs.libraryId}") {
                    libraryDao.getById(abs.serverId, abs.libraryId)?.name ?: abs.libraryId
                }
                AbsPickerItem(
                    absServerId = row.serverId,
                    absLibraryItemId = row.itemId,
                    title = abs.title,
                    author = abs.author,
                    libraryName = name,
                    coverUrl = abs.coverUrl,
                )
            }
    }

    private suspend fun createUserConfirmedLink(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    ) {
        val now = clock()
        val existing = linkDao.findByAbsItem(absServerId, absLibraryItemId)
        linkDao.upsert(
            ReadaloudLinkEntity(
                absServerId = absServerId,
                absLibraryItemId = absLibraryItemId,
                storytellerServerId = storytellerServerId,
                storytellerBookId = storytellerBookId,
                state = ReadaloudLinkEntity.STATE_CONFIRMED,
                userConfirmed = true,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        )
    }
}

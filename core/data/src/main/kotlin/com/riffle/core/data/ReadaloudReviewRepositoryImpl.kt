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
import com.riffle.core.domain.AbsFormatFilter
import com.riffle.core.domain.AbsPickerItem
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.AudioPlaybackPreferencesStore
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
    private val audioIdentityResolver: AudioIdentityResolver,
    private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
    private val clock: () -> Long,
) : ReadaloudReviewRepository {

    @Inject constructor(
        libraryItemDao: LibraryItemDao,
        libraryDao: LibraryDao,
        linkDao: ReadaloudLinkDao,
        candidateDao: ReadaloudCandidateDao,
        dismissalDao: ReadaloudDismissalDao,
        audioIdentityResolver: AudioIdentityResolver,
        audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
    ) : this(
        libraryItemDao, libraryDao, linkDao, candidateDao, dismissalDao,
        audioIdentityResolver, audioPlaybackPreferencesStore, System::currentTimeMillis,
    )

    override fun observeReview(storytellerServerId: String, absServerId: String?): Flow<ReadaloudReview> =
        combine(
            linkDao.observeAll(),
            candidateDao.observeForStorytellerServer(storytellerServerId),
        ) { links, candidates ->
            // When the screen scopes to one ABS Server, both confirmed links and pending
            // candidates are filtered to that server so each ABS account (= one server+login)
            // shows its own self-contained match set. Without this, a readaloud auto-matched
            // against the same title on two ABS accounts piles their links into one card
            // ("2 ebook + 2 audiobook" per match). Switching the active ABS account flips the
            // visible set; nothing is permanently hidden.
            val scopedLinks = if (absServerId == null) links
                else links.filter { it.absServerId == absServerId }
            val scopedCandidates = if (absServerId == null) candidates
                else candidates.filter { it.absServerId == absServerId }
            buildReview(storytellerServerId, scopedLinks, scopedCandidates)
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
                        hasEbook = abs.hasEbook(),
                        hasAudio = abs.hasAudio,
                        identityResult = runCatching { com.riffle.core.domain.AudiobookIdentityResult.valueOf(link.identityResult) }
                            .getOrDefault(com.riffle.core.domain.AudiobookIdentityResult.UNKNOWN),
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
            // Alphabetical by title; the screen splits these into "Partially matched" vs "Matched"
            // sections via ConfirmedReadaloud.isIncomplete, so each section stays in name order.
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
        rekeyAudioSettingsAround(storytellerServerId, storytellerBookId) {
            createUserConfirmedLink(storytellerServerId, storytellerBookId, absServerId, absLibraryItemId)
            // The book is now Confirmed; drop all of its Pending-Review candidates.
            candidateDao.deleteByStorytellerBook(storytellerServerId, storytellerBookId)
        }
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
        rekeyAudioSettingsAround(storytellerServerId, storytellerBookId) {
            linkDao.deleteByStorytellerBook(storytellerServerId, storytellerBookId)
        }
    }

    override suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String) {
        val link = linkDao.findByAbsItem(absServerId, absLibraryItemId)
        if (link == null) {
            linkDao.deleteByAbsItem(absServerId, absLibraryItemId)
            return
        }
        rekeyAudioSettingsAround(link.storytellerServerId, link.storytellerBookId) {
            linkDao.deleteByAbsItem(absServerId, absLibraryItemId)
        }
    }

    override suspend fun pairManually(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    ) {
        rekeyAudioSettingsAround(storytellerServerId, storytellerBookId) {
            createUserConfirmedLink(storytellerServerId, storytellerBookId, absServerId, absLibraryItemId)
            // Manual pairing overrides any prior "don't ask again" and clears stale candidates.
            dismissalDao.clearBookDismissal(storytellerServerId, storytellerBookId)
            candidateDao.deleteByStorytellerBook(storytellerServerId, storytellerBookId)
        }
    }

    /**
     * Runs [mutate] (a link/unlink change), then migrates the per-book audio-settings record if the
     * change moved the readaloud's canonical audio identity (ADR 0028) — e.g. linking an audiobook
     * moves the saved speed from the Storyteller id onto the audiobook id; unlinking moves it back.
     */
    private suspend fun rekeyAudioSettingsAround(
        storytellerServerId: String,
        storytellerBookId: String,
        mutate: suspend () -> Unit,
    ) {
        val before = audioIdentityResolver.resolveForStorytellerBook(storytellerServerId, storytellerBookId)
        mutate()
        val after = audioIdentityResolver.resolveForStorytellerBook(storytellerServerId, storytellerBookId)
        if (before != after) audioPlaybackPreferencesStore.rekey(before, after)
    }

    override suspend fun searchAbsItems(absServerId: String, query: String, filter: AbsFormatFilter): List<AbsPickerItem> {
        if (absServerId.isEmpty()) return emptyList()
        val trimmed = query.trim()
        val all = libraryItemDao.listMatchableByServerType(ServerType.AUDIOBOOKSHELF.name)
            .filter { it.serverId == absServerId }
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
                val hasEbook = abs.hasEbook()
                // Strict filter: tapping the ebook slot only offers items with an ebook, and the
                // audio slot only items with audio (a combined item satisfies both).
                val keep = when (filter) {
                    AbsFormatFilter.ANY -> true
                    AbsFormatFilter.EBOOK -> hasEbook
                    AbsFormatFilter.AUDIO -> abs.hasAudio
                }
                if (!keep) return@mapNotNull null
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
                    hasEbook = hasEbook,
                    hasAudio = abs.hasAudio,
                )
            }
    }

    /** True when this ABS item carries a readable ebook (epub/pdf), i.e. not an audio-only stub. */
    private fun LibraryItemEntity.hasEbook(): Boolean =
        EbookFormat.from(ebookFormat) != EbookFormat.Unsupported

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

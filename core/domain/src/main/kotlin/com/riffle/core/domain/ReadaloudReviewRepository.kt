package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Read-only surface over the auto-matcher's persisted verdicts (links + pending candidates) plus
 * the user's sticky decisions (dismissals). Action mutations live on
 * [ReadaloudReviewMutator] / [com.riffle.core.domain.usecase.ReadaloudReviewActions].
 */
interface ReadaloudReviewRepository {

    /**
     * The live three-section review for one Storyteller Server. [absSourceId] scopes the
     * pending-candidate suggestions to a single ABS Server — the one the user is currently
     * matching against — so two ABS accounts pointing at the same library don't produce
     * duplicate suggestions for every readaloud. Pass `null` to keep the legacy cross-server
     * behaviour (used only by stable test scaffolding); production callers pass the active
     * ABS Server's id. Confirmed/Partial matches are NOT filtered — they stay visible across
     * Servers so existing links can be inspected or unlinked.
     */
    fun observeReview(storytellerSourceId: String, absSourceId: String? = null): Flow<ReadaloudReview>

    /**
     * Search ABS Library Items on a single ABS Server by title/author. [absSourceId] scopes
     * the search to one ABS Server — the one the user is currently matching against — so
     * two ABS accounts pointing at the same library don't produce identical-looking
     * duplicate rows. An empty [absSourceId] returns no results.
     *
     * [filter] narrows results to items that can fill a specific missing slot
     * (ebook / audio) of a Confirmed match; [AbsFormatFilter.ANY] is the unfiltered
     * manual-pairing search.
     */
    suspend fun searchAbsItems(
        absSourceId: String,
        query: String,
        filter: AbsFormatFilter = AbsFormatFilter.ANY,
    ): List<AbsPickerItem>
}

/**
 * Low-level mutator over the readaloud-link / candidate / dismissal tables. Implementations are
 * pure DAO wrappers — no audio-settings rekey, no clock; that choreography lives in
 * [com.riffle.core.domain.usecase.ReadaloudReviewActions].
 */
interface ReadaloudReviewMutator {

    /** Insert or update a user-confirmed link from a Storyteller book to an ABS item. */
    suspend fun createUserConfirmedLink(
        storytellerSourceId: String,
        storytellerBookId: String,
        absSourceId: String,
        absLibraryItemId: String,
    )

    /** Drop every pending candidate for this Storyteller book. */
    suspend fun deleteCandidatesForBook(storytellerSourceId: String, storytellerBookId: String)

    /** Sticky "this specific candidate is wrong, don't ask again". */
    suspend fun upsertCandidateDismissal(
        storytellerSourceId: String,
        storytellerBookId: String,
        absSourceId: String,
        absLibraryItemId: String,
    )

    /** Drop one pending candidate (called after dismissing it). */
    suspend fun deleteCandidate(
        storytellerSourceId: String,
        storytellerBookId: String,
        absSourceId: String,
        absLibraryItemId: String,
    )

    /** Sticky "this book has no readaloud match, don't ask again". */
    suspend fun upsertBookDismissal(storytellerSourceId: String, storytellerBookId: String)

    /** Drop a prior book-level dismissal (manual pairing overrides it). */
    suspend fun clearBookDismissal(storytellerSourceId: String, storytellerBookId: String)

    /** Remove every link for this Storyteller book (returns the book to Unmatched). */
    suspend fun deleteLinksForStorytellerBook(storytellerSourceId: String, storytellerBookId: String)

    /** Remove one ABS item's link. */
    suspend fun deleteLinkForAbsItem(absSourceId: String, absLibraryItemId: String)
}

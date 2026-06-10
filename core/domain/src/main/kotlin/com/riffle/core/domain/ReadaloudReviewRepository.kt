package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Backs the Readaloud-matches review queue (ADR 0021). Reads the auto-matcher's persisted
 * verdicts and the user's sticky decisions, and applies the per-candidate / per-book actions.
 */
interface ReadaloudReviewRepository {

    /** The live three-section review for one Storyteller Server. */
    fun observeReview(storytellerServerId: String): Flow<ReadaloudReview>

    /** Confirm a candidate: create a sticky `userConfirmed` link and clear the book's candidates. */
    suspend fun confirmCandidate(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    )

    /** Dismiss one candidate so this exact pair never re-surfaces in Pending Review. */
    suspend fun dismissCandidate(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    )

    /** "No match — don't ask again": the book moves to Unmatched and stays there. */
    suspend fun dismissBook(storytellerServerId: String, storytellerBookId: String)

    /**
     * Unlink a Confirmed readaloud — removes **every** ABS row paired with it so the book returns
     * to Unmatched in one action (a readaloud can be linked to several ABS items at once).
     */
    suspend fun unlinkBook(storytellerServerId: String, storytellerBookId: String)

    /** Unlink a single ABS item from a readaloud, leaving any other links intact (used by the picker). */
    suspend fun unlinkAbsItem(absServerId: String, absLibraryItemId: String)

    /**
     * Manually pair an Unmatched readaloud to an ABS item the user chose from the picker. Creates a
     * sticky `userConfirmed` link and clears any prior "don't ask again" / candidate state.
     */
    suspend fun pairManually(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    )

    /**
     * Search ABS Library Items across every configured ABS Server by title/author. [filter] narrows
     * results to items that can fill a specific missing slot (ebook / audio) of a Confirmed match;
     * [AbsFormatFilter.ANY] is the unfiltered manual-pairing search.
     */
    suspend fun searchAbsItems(
        query: String,
        filter: AbsFormatFilter = AbsFormatFilter.ANY,
    ): List<AbsPickerItem>
}

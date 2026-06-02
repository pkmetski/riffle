package com.riffle.core.domain

/**
 * The full review surface for one Storyteller Server's readalouds (ADR 0021): the three sections
 * shown under Settings → [Storyteller Server] → Readaloud matches.
 */
data class ReadaloudReview(
    val pending: List<PendingReadaloud>,
    val unmatched: List<UnmatchedReadaloud>,
    val confirmed: List<ConfirmedReadaloud>,
)

/** A readaloud the auto-matcher couldn't auto-Confirm, with its above-threshold ABS candidates. */
data class PendingReadaloud(
    val storytellerServerId: String,
    val storytellerBookId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val candidates: List<AbsCandidate>,
)

/** One scored ABS candidate for a [PendingReadaloud]. */
data class AbsCandidate(
    val absServerId: String,
    val absLibraryItemId: String,
    val absTitle: String,
    val absAuthor: String,
    val absLibraryName: String,
    val coverUrl: String?,
    val score: Double,
)

/** A readaloud with no Confirmed link and no surviving candidates — available for manual pairing. */
data class UnmatchedReadaloud(
    val storytellerServerId: String,
    val storytellerBookId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
)

/**
 * A Confirmed readaloud, grouped per book. A readaloud legitimately links to more than one ABS
 * item (e.g. an ebook entry and an audiobook stub of the same work), so all of them are listed
 * here and Unlink detaches the whole match at once — un-matching the book in a single action.
 */
data class ConfirmedReadaloud(
    val storytellerServerId: String,
    val storytellerBookId: String,
    val title: String,
    val targets: List<ConfirmedTarget>,
) {
    data class ConfirmedTarget(
        val absServerId: String,
        val absLibraryItemId: String,
        val absTitle: String,
        val absLibraryName: String,
    )
}

/** A searchable ABS Library Item shown in the "Match manually…" picker, across every ABS Server. */
data class AbsPickerItem(
    val absServerId: String,
    val absLibraryItemId: String,
    val title: String,
    val author: String,
    val libraryName: String,
    val coverUrl: String?,
)

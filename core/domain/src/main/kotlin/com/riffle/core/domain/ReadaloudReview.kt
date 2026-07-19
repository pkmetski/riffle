package com.riffle.core.domain
import com.riffle.core.models.AudiobookIdentityResult

/**
 * The full review surface for one Storyteller Service's readalouds (ADR 0021): the three sections
 * shown under Settings → [Storyteller Service] → Readaloud matches.
 */
data class ReadaloudReview(
    val pending: List<PendingReadaloud>,
    val unmatched: List<UnmatchedReadaloud>,
    val confirmed: List<ConfirmedReadaloud>,
)

/** A readaloud the auto-matcher couldn't auto-Confirm, with its above-threshold ABS candidates. */
data class PendingReadaloud(
    val storytellerSourceId: String,
    val storytellerBookId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val candidates: List<AbsCandidate>,
)

/** One scored ABS candidate for a [PendingReadaloud]. */
data class AbsCandidate(
    val absSourceId: String,
    val absLibraryItemId: String,
    val absTitle: String,
    val absAuthor: String,
    val absLibraryName: String,
    val coverUrl: String?,
    val score: Double,
)

/** A readaloud with no Confirmed link and no surviving candidates — available for manual pairing. */
data class UnmatchedReadaloud(
    val storytellerSourceId: String,
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
    val storytellerSourceId: String,
    val storytellerBookId: String,
    val title: String,
    val targets: List<ConfirmedTarget>,
) {
    /** True when at least one linked item supplies an ebook (a combined item counts). */
    val hasEbook: Boolean get() = targets.any { it.hasEbook }

    /** True when at least one linked item supplies audio (a combined item counts). */
    val hasAudio: Boolean get() = targets.any { it.hasAudio }

    /** Missing one of the two sides — surfaced above complete matches so the gap is easy to spot. */
    val isIncomplete: Boolean get() = !hasEbook || !hasAudio

    data class ConfirmedTarget(
        val absSourceId: String,
        val absLibraryItemId: String,
        val absTitle: String,
        val absLibraryName: String,
        // Which side(s) of the readaloud this ABS item supplies. A "combined" item carries both,
        // so it fills both slots; a one-sided match leaves the other slot empty (the missing side).
        val hasEbook: Boolean,
        val hasAudio: Boolean,
        val identityResult: AudiobookIdentityResult = AudiobookIdentityResult.UNKNOWN,
    )

    /** Derived streaming status for the matches screen (ADR 0028). */
    val streamingStatus: StreamingStatus
        get() {
            val audiobook = targets.firstOrNull { it.hasAudio }
            return when {
                audiobook == null -> StreamingStatus.DOWNLOAD_ONLY_NO_AUDIOBOOK
                audiobook.identityResult == AudiobookIdentityResult.VERIFIED -> StreamingStatus.STREAMING
                audiobook.identityResult == AudiobookIdentityResult.MISMATCH -> StreamingStatus.DOWNLOAD_ONLY_MISMATCH
                else -> StreamingStatus.UNKNOWN
            }
        }

    enum class StreamingStatus { STREAMING, DOWNLOAD_ONLY_NO_AUDIOBOOK, DOWNLOAD_ONLY_MISMATCH, UNKNOWN }
}

/** A searchable ABS Library Item shown in the "Match manually…" picker, across every ABS Server. */
data class AbsPickerItem(
    val absSourceId: String,
    val absLibraryItemId: String,
    val title: String,
    val author: String,
    val libraryName: String,
    val coverUrl: String?,
    val hasEbook: Boolean,
    val hasAudio: Boolean,
)

/**
 * Restricts the manual picker to items that can fill a specific missing slot of a Confirmed match:
 * [EBOOK] / [AUDIO] keep only items supplying that side; [ANY] is the unfiltered "Match manually…"
 * flow used from Unmatched rows.
 */
enum class AbsFormatFilter { ANY, EBOOK, AUDIO }

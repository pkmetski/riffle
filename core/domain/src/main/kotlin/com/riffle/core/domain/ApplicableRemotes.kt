package com.riffle.core.domain

/** A reconcilable position holder for the open book. */
enum class RemoteKind { ABS_EBOOK, ABS_AUDIO, STORYTELLER }

/**
 * The match- and prerequisite-state that decides a book's applicable remote set (ADR 0019, as
 * amended by ADR 0026). A book is always read from the ABS side, so the canonical frame is always
 * the ABS EPUB and the single-peer set is always `{ABS ebook}`.
 *
 * The readaloud bundle is the hub: progress flows to whichever ABS items are matched to it. The
 * ebook progress (an `ebookLocation` CFI) goes to a matched item that has an ebook; the audiobook
 * progress (a `currentTime`) goes to a matched item that has audio. These may be the same combined
 * ABS item, or — when a library keeps ebooks and audiobooks as separate items — two distinct ones.
 *
 * @param isMatched a Confirmed [ReadaloudLink] exists for the open book.
 * @param hasAbsEbookTarget a matched ABS item carries an ebook (→ the `ABS_EBOOK` remote).
 * @param hasAbsAudioTarget a matched ABS item carries audio (→ the `ABS_AUDIO` remote).
 * @param prerequisitesCached the Storyteller EPUB bundle and cross-EPUB index are available.
 */
data class BookSyncState(
    val isMatched: Boolean,
    val hasAbsEbookTarget: Boolean,
    val hasAbsAudioTarget: Boolean,
    val prerequisitesCached: Boolean,
)

/**
 * The set of remotes a sync cycle reconciles for the open book.
 *
 * - Unmatched: single-peer `{ABS ebook}`.
 * - Matched, prerequisites not yet cached: single-peer `{ABS ebook}` (the displayed frame) until
 *   the prerequisites land, then it upgrades on a later cycle.
 * - Matched, prerequisites cached: the remotes for whichever ABS targets are matched, plus
 *   Storyteller. Ebook-and-audio matched on one combined item or two separate items both yield
 *   `{ABS ebook, ABS audio, Storyteller}`; an ebook-only match yields `{ABS ebook, Storyteller}`.
 */
fun applicableRemotes(state: BookSyncState): Set<RemoteKind> {
    if (!state.isMatched) return setOf(RemoteKind.ABS_EBOOK)

    if (!state.prerequisitesCached) return setOf(RemoteKind.ABS_EBOOK)

    return buildSet {
        if (state.hasAbsEbookTarget) add(RemoteKind.ABS_EBOOK)
        if (state.hasAbsAudioTarget) add(RemoteKind.ABS_AUDIO)
        add(RemoteKind.STORYTELLER)
    }
}

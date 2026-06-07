package com.riffle.core.domain

/**
 * A reconcilable position holder for the open book.
 *
 * The ABS **audiobook** is deliberately NOT a [RemoteKind]: it is never reconciled inbound. The
 * audio clock can run ahead of (or behind) the reading position — readaloud may start behind the
 * page, and the live audio time is pushed to the audiobook while listening. If the audiobook were a
 * reconciled peer, that fresh-but-divergent record would win the cycle and propagate the audio
 * position to the **ebook**, erasing the reading position (the repeated data-loss failure mode). The
 * audiobook is therefore written ONLY by a separate, push-only update of its `currentTime`
 * (`ThreePeerReaderSyncCoordinator.pushAudiobookSeconds`) — never read back, never driving the ebook.
 */
enum class RemoteKind { ABS_EBOOK, STORYTELLER }

/**
 * The match- and prerequisite-state that decides a book's applicable remote set (ADR 0019, as
 * amended by ADR 0026). A book is always read from the ABS side, so the canonical frame is always
 * the ABS EPUB and the single-peer set is always `{ABS ebook}`.
 *
 * The readaloud bundle is the hub: ebook progress (an `ebookLocation` CFI) flows to a matched item
 * that has an ebook. The audiobook is push-only (see [RemoteKind]); [hasAbsAudioTarget] only gates
 * whether a push endpoint is built, it does NOT add a reconciled remote.
 *
 * @param isMatched a Confirmed [ReadaloudLink] exists for the open book.
 * @param hasAbsEbookTarget a matched ABS item carries an ebook (→ the `ABS_EBOOK` remote).
 * @param hasAbsAudioTarget a matched ABS item carries audio (→ a push-only audiobook target, NOT a
 *   reconciled remote).
 * @param prerequisitesCached the Storyteller EPUB bundle and cross-EPUB index are available.
 */
data class BookSyncState(
    val isMatched: Boolean,
    val hasAbsEbookTarget: Boolean,
    val hasAbsAudioTarget: Boolean,
    val prerequisitesCached: Boolean,
)

/**
 * The set of remotes a sync cycle reconciles for the open book. The audiobook is never in this set
 * (push-only, see [RemoteKind]).
 *
 * - Unmatched: single-peer `{ABS ebook}`.
 * - Matched, prerequisites not yet cached: single-peer `{ABS ebook}` (the displayed frame) until
 *   the prerequisites land, then it upgrades on a later cycle.
 * - Matched, prerequisites cached: `{ABS ebook, Storyteller}` (ebook only when a matched item has
 *   an ebook), plus Storyteller as the cross-EPUB text peer.
 */
fun applicableRemotes(state: BookSyncState): Set<RemoteKind> {
    if (!state.isMatched) return setOf(RemoteKind.ABS_EBOOK)

    if (!state.prerequisitesCached) return setOf(RemoteKind.ABS_EBOOK)

    return buildSet {
        if (state.hasAbsEbookTarget) add(RemoteKind.ABS_EBOOK)
        add(RemoteKind.STORYTELLER)
    }
}

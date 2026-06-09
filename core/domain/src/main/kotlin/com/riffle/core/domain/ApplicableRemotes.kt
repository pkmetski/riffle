package com.riffle.core.domain

/**
 * A reconcilable position holder for the open book.
 *
 * The ABS **audiobook** (`ABS_AUDIO`) is reconciled **both ways**, like the other peers: the cycle
 * reads its `currentTime` (a genuinely-newer listen on another device / the ABS app wins and moves the
 * reader) and writes it when the reading position is the winner (so reading advances the audiobook
 * forward). A separate responsive push (`ThreePeerReaderSyncCoordinator.pushAudiobook*`) also writes it
 * on a tight cadence while readaloud plays, from the exact narrated sentence.
 *
 * The feedback loop that erased the ebook (our own fresh-timestamped write read back next cycle as a
 * "newer remote" and drove the ebook to the audio position) is closed at the timestamp layer: every
 * write records the server's returned `lastUpdate` as the local timestamp, so it reads back as equal
 * (local wins ties), never newer — and a remote-win jump keeps that adopted server time rather than
 * re-stamping `now` (see EpubReaderViewModel.pendingServerJumpStamp). Only a position genuinely written
 * by some other client outranks the reading position.
 */
enum class RemoteKind { ABS_EBOOK, ABS_AUDIO, STORYTELLER }

/**
 * The match- and prerequisite-state that decides a book's applicable remote set (ADR 0019, as
 * amended by ADR 0026). A book is always read from the ABS side, so the canonical frame is always
 * the ABS EPUB and the single-peer set is always `{ABS ebook}`.
 *
 * The readaloud bundle is the hub: ebook progress (an `ebookLocation` CFI) flows to a matched item
 * that has an ebook; the audiobook is reconciled both ways (see [RemoteKind]) so a cross-device listen
 * is reflected locally and reading advances it forward.
 *
 * @param isMatched a Confirmed [ReadaloudLink] exists for the open book.
 * @param hasAbsEbookTarget a matched ABS item carries an ebook (→ the `ABS_EBOOK` remote).
 * @param hasAbsAudioTarget a matched ABS item carries audio (→ the `ABS_AUDIO` remote, reconciled both
 *   ways, plus the responsive playback push).
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
 *   Storyteller. `ABS_AUDIO` is included when a matched item has audio — reconciled both ways (the
 *   cycle reads and writes it), with a separate responsive push while readaloud plays.
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

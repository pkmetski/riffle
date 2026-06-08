package com.riffle.core.domain

/**
 * A reconcilable position holder for the open book.
 *
 * The ABS **audiobook** (`ABS_AUDIO`) is reconciled **inbound-only**: a sync cycle reads its
 * `currentTime` so a genuinely-newer listen (from another device / the ABS app) can win and move the
 * reader, but the cycle never *writes* it. Outbound to the audiobook is a separate, push-only update
 * of its `currentTime` from the live audio clock (`ThreePeerReaderSyncCoordinator.pushAudiobookSeconds`).
 *
 * The feedback loop that erased the ebook (our own fresh-timestamped push read back as a "newer
 * remote" and driving the ebook to the audio position) is closed at the timestamp layer, NOT by
 * dropping the peer: the push records the server's returned `lastUpdate` as the local timestamp, so
 * our own write reads back as equal (local wins ties), never newer. Only a position written by some
 * other client outranks the reading position.
 */
enum class RemoteKind { ABS_EBOOK, ABS_AUDIO, STORYTELLER }

/**
 * The match- and prerequisite-state that decides a book's applicable remote set (ADR 0019, as
 * amended by ADR 0026). A book is always read from the ABS side, so the canonical frame is always
 * the ABS EPUB and the single-peer set is always `{ABS ebook}`.
 *
 * The readaloud bundle is the hub: ebook progress (an `ebookLocation` CFI) flows to a matched item
 * that has an ebook; the audiobook is reconciled inbound (see [RemoteKind]) so a cross-device listen
 * is reflected locally.
 *
 * @param isMatched a Confirmed [ReadaloudLink] exists for the open book.
 * @param hasAbsEbookTarget a matched ABS item carries an ebook (→ the `ABS_EBOOK` remote).
 * @param hasAbsAudioTarget a matched ABS item carries audio (→ the inbound `ABS_AUDIO` remote and a
 *   push-only outbound target).
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
 *   Storyteller. `ABS_AUDIO` is included when a matched item has audio — reconciled inbound-only
 *   (the cycle reads it but never writes it; outbound is the push).
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

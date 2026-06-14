package com.riffle.core.domain

/**
 * A reconcilable position holder for the open book. Two ABS records are reconciled: the ebook
 * (`ABS_EBOOK`, an `ebookLocation` CFI) and the audiobook (`ABS_AUDIO`, a `currentTime` in seconds).
 * They are the same logical position in two representations, bridged by the readaloud bundle's SMIL
 * (ADR 0029). Riffle no longer reconciles Storyteller's own position record — it is not a sync peer
 * (the bundle is used only to translate audio↔text, never written back to Storyteller).
 *
 * `ABS_AUDIO` is reconciled **both ways**: the cycle reads its `currentTime` (a genuinely-newer listen
 * on another device / the ABS app wins and moves the reader) and writes it when the reading position
 * is the winner (so reading advances the audiobook). A separate responsive push
 * (`ReaderSyncCoordinator.pushAudiobook*`) also writes it on a tight cadence while readaloud
 * plays, from the exact narrated sentence.
 *
 * The feedback loop that erased the ebook (our own fresh-timestamped write read back next cycle as a
 * "newer remote" and drove the ebook to the audio position) is closed at the timestamp layer: every
 * write records the server's returned `lastUpdate` as the local timestamp, so it reads back as equal
 * (local wins ties), never newer — and a remote-win jump keeps that adopted server time rather than
 * re-stamping `now` (see EpubReaderViewModel.pendingServerJumpStamp). Only a position genuinely written
 * by some other client outranks the reading position.
 */
enum class RemoteKind { ABS_EBOOK, ABS_AUDIO, ABS_BOOKMARK }

/**
 * The medium the open book is being driven from — i.e. which surface holds the canonical position.
 *
 * The reader is **text-led** (canonical = the displayed EPUB locator); the [Audiobook Player] is
 * **audio-led** (canonical = audio seconds). This selects the single-peer base frame so an
 * audiobook-only item reconciles its own ABS audio record rather than a non-existent ebook (ADR 0029).
 */
enum class OpenedMedium { EBOOK, AUDIO }

/**
 * The match- and prerequisite-state that decides a book's applicable remote set (ADR 0019, as
 * amended by ADR 0026). A book is always read from the ABS side, so the canonical frame is always
 * the ABS EPUB and the single-peer set is always `{ABS ebook}`.
 *
 * The readaloud bundle bridges the two ABS records: ebook progress (an `ebookLocation` CFI) and the
 * audiobook (`currentTime`, reconciled both ways — see [RemoteKind]) are the same logical position,
 * translated through the bundle's SMIL. Storyteller's own position is not a peer (ADR 0029).
 *
 * @param isMatched a Confirmed [ReadaloudLink] exists for the open book.
 * @param openedMedium which medium the book was opened from — the reader (EBOOK) or the audiobook
 *   player (AUDIO). Selects the single-peer base frame: an audiobook-only item has no ebook, so its
 *   base is `ABS_AUDIO`, not `ABS_EBOOK` (ADR 0029). Defaults to EBOOK so existing reader call sites
 *   are unchanged.
 * @param hasAbsEbookTarget a matched ABS item carries an ebook (→ the `ABS_EBOOK` remote).
 * @param hasAbsAudioTarget a matched ABS item carries audio (→ the `ABS_AUDIO` remote, reconciled both
 *   ways, plus the responsive playback push).
 * @param prerequisitesCached the Storyteller EPUB bundle and cross-EPUB index are available.
 */
data class BookSyncState(
    val isMatched: Boolean,
    val openedMedium: OpenedMedium = OpenedMedium.EBOOK,
    val hasAbsEbookTarget: Boolean,
    val hasAbsAudioTarget: Boolean,
    val prerequisitesCached: Boolean,
)

/**
 * The set of remotes a sync cycle reconciles for the open book.
 *
 * - Unmatched: single-peer base frame — `{ABS ebook}` when opened from the reader, `{ABS audio}`
 *   when opened from the audiobook player (ADR 0029).
 * - Matched, prerequisites not yet cached: the single-peer base frame (the displayed/audible medium)
 *   until the prerequisites land, then it upgrades on a later cycle.
 * - Matched, prerequisites cached: the remotes for whichever ABS targets are matched. `ABS_AUDIO` is
 *   included when a matched item has audio — reconciled both ways (the cycle reads and writes it),
 *   with a separate responsive push while readaloud plays. Storyteller is not a peer (ADR 0029).
 */
fun applicableRemotes(state: BookSyncState): Set<RemoteKind> {
    val base = when (state.openedMedium) {
        OpenedMedium.EBOOK -> RemoteKind.ABS_EBOOK
        OpenedMedium.AUDIO -> RemoteKind.ABS_AUDIO
    }

    if (!state.isMatched) return setOf(base)

    if (!state.prerequisitesCached) return setOf(base)

    return buildSet {
        if (state.hasAbsEbookTarget) add(RemoteKind.ABS_EBOOK)
        if (state.hasAbsAudioTarget) add(RemoteKind.ABS_AUDIO)
    }
}

package com.riffle.core.domain

/**
 * A reconcilable position holder for the open book. Two position records are reconciled per
 * matched book: the ebook (`EBOOK_POSITION`, a locator on the ebook peer) and the audiobook
 * (`AUDIO_POSITION`, a `currentTime` in seconds on the audio peer). They are the same logical
 * position in two representations, bridged by the readaloud bundle's SMIL (ADR 0029). Storyteller
 * is not a sync peer (the bundle is used only to translate audio↔text, never written back).
 *
 * `AUDIO_POSITION` is reconciled **both ways**: the cycle reads its `currentTime` (a genuinely-
 * newer listen on another device wins and moves the reader) and writes it when the reading
 * position is the winner (so reading advances the audiobook). A separate responsive push
 * (`ReaderSyncCoordinator.pushAudiobook*`) also writes it on a tight cadence while readaloud
 * plays, from the exact narrated sentence.
 *
 * The names are source-agnostic — any Catalog implementing `ProgressPeerCapability` slots in as
 * the ebook peer (ABS today; Komga in #528). `AudiobookProgressPeerCapability` gates the audio
 * peer (ABS only today — Komga has no audio media).
 */
enum class RemoteKind { EBOOK_POSITION, AUDIO_POSITION, AUDIOBOOK_BOOKMARK }

/**
 * The medium the open book is being driven from — i.e. which surface holds the canonical position.
 *
 * The reader is **text-led** (canonical = the displayed EPUB locator); the [Audiobook Player] is
 * **audio-led** (canonical = audio seconds). This selects the single-peer base frame so an
 * audiobook-only item reconciles its own audio record rather than a non-existent ebook (ADR 0029).
 */
enum class OpenedMedium { EBOOK, AUDIO }

/**
 * The match- and prerequisite-state that decides a book's applicable remote set (ADR 0019, as
 * amended by ADR 0026 and refined for source-agnostic peers by #528). A book is always read from
 * an ebook peer, so the canonical frame is always the peer's EPUB and the single-peer set is
 * always `{ebook}`.
 *
 * The readaloud bundle bridges the two records: ebook progress and the audiobook `currentTime`
 * are the same logical position, translated through the bundle's SMIL. Storyteller's own position
 * is not a peer (ADR 0029).
 *
 * @param isMatched a Confirmed [ReadaloudLink] exists for the open book.
 * @param openedMedium which medium the book was opened from — the reader (EBOOK) or the audiobook
 *   player (AUDIO). Selects the single-peer base frame: an audiobook-only item has no ebook, so its
 *   base is `AUDIO_POSITION`, not `EBOOK_POSITION` (ADR 0029). Defaults to EBOOK so existing
 *   reader call sites are unchanged.
 * @param hasEbookPeer a matched item carries an ebook (→ the `EBOOK_POSITION` remote).
 * @param hasAudioPeer a matched item carries audio (→ the `AUDIO_POSITION` remote, reconciled
 *   both ways, plus the responsive playback push). Requires the source Catalog to implement
 *   `AudiobookProgressPeerCapability`.
 * @param prerequisitesCached the Storyteller EPUB bundle and cross-EPUB index are available.
 */
data class BookSyncState(
    val isMatched: Boolean,
    val openedMedium: OpenedMedium = OpenedMedium.EBOOK,
    val hasEbookPeer: Boolean,
    val hasAudioPeer: Boolean,
    val prerequisitesCached: Boolean,
)

/**
 * The set of remotes a sync cycle reconciles for the open book.
 *
 * - Unmatched: single-peer base frame — `{ebook}` when opened from the reader, `{audio}` when
 *   opened from the audiobook player (ADR 0029).
 * - Matched, prerequisites not yet cached: the single-peer base frame (the displayed/audible
 *   medium) until the prerequisites land, then it upgrades on a later cycle.
 * - Matched, prerequisites cached: the remotes for whichever peers exist. `AUDIO_POSITION` is
 *   included when the matched item has audio — reconciled both ways (the cycle reads and writes
 *   it), with a separate responsive push while readaloud plays. Storyteller is not a peer (ADR 0029).
 */
fun applicableRemotes(state: BookSyncState): Set<RemoteKind> {
    val base = when (state.openedMedium) {
        OpenedMedium.EBOOK -> RemoteKind.EBOOK_POSITION
        OpenedMedium.AUDIO -> RemoteKind.AUDIO_POSITION
    }

    if (!state.isMatched) return setOf(base)

    if (!state.prerequisitesCached) return setOf(base)

    return buildSet {
        if (state.hasEbookPeer) add(RemoteKind.EBOOK_POSITION)
        if (state.hasAudioPeer) add(RemoteKind.AUDIO_POSITION)
    }
}

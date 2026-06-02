package com.riffle.core.domain

/** A reconcilable position holder for the open book. */
enum class RemoteKind { ABS_EBOOK, ABS_AUDIO, STORYTELLER }

/** Which entry point the book was opened from — determines single-peer fallback. */
enum class OpenedSide { ABS, READALOUD }

/**
 * The match- and prerequisite-state that decides a book's applicable remote set (ADR 0019).
 *
 * @param isMatched a Confirmed [ReadaloudLink] exists for the open book.
 * @param confirmedAbsLinkCount how many Confirmed ABS links the Storyteller readaloud holds;
 *   the three-peer invariant requires exactly one (one readaloud ↔ one ABS Library Item).
 * @param prerequisitesCached the Storyteller EPUB bundle and cross-EPUB index are available.
 * @param openedSide the entry point, which selects the single-peer fallback set.
 */
data class BookSyncState(
    val isMatched: Boolean,
    val confirmedAbsLinkCount: Int,
    val prerequisitesCached: Boolean,
    val openedSide: OpenedSide,
)

/**
 * The set of remotes a sync cycle reconciles for the open book.
 *
 * - Unmatched: single-peer, the open side's own remote (`{ABS ebook}` / `{Storyteller}`).
 * - Matched, **exactly one** ABS link, prerequisites cached: three-peer from either side.
 * - Matched, **more than one** ABS link: the multi-link guard — those are distinct users'
 *   progress sharing one Storyteller position, so Storyteller is excluded and the book
 *   degrades to a two-peer ABS-ebook ↔ ABS-audiobook cycle.
 * - Matched, one ABS link, prerequisites not yet cached: single-peer side fallback until
 *   the prerequisites land, then it upgrades on a later cycle.
 */
fun applicableRemotes(state: BookSyncState): Set<RemoteKind> {
    val sidePeer = when (state.openedSide) {
        OpenedSide.ABS -> setOf(RemoteKind.ABS_EBOOK)
        OpenedSide.READALOUD -> setOf(RemoteKind.STORYTELLER)
    }

    if (!state.isMatched) return sidePeer

    if (state.confirmedAbsLinkCount > 1) {
        // Multi-link guard: never write the shared Storyteller position.
        return setOf(RemoteKind.ABS_EBOOK, RemoteKind.ABS_AUDIO)
    }

    if (!state.prerequisitesCached) return sidePeer

    return setOf(RemoteKind.ABS_EBOOK, RemoteKind.ABS_AUDIO, RemoteKind.STORYTELLER)
}

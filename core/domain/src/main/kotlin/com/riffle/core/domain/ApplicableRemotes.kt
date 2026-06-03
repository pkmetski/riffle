package com.riffle.core.domain

/** A reconcilable position holder for the open book. */
enum class RemoteKind { ABS_EBOOK, ABS_AUDIO, STORYTELLER }

/**
 * The match- and prerequisite-state that decides a book's applicable remote set (ADR 0019, as
 * amended by ADR 0026). A book is always read from the ABS side, so the canonical frame is always
 * the ABS EPUB and the single-peer set is always `{ABS ebook}`.
 *
 * @param isMatched a Confirmed [ReadaloudLink] exists for the open book.
 * @param confirmedAbsLinkCount how many Confirmed ABS links the Storyteller readaloud holds;
 *   the three-peer invariant requires exactly one (one readaloud ↔ one ABS Library Item).
 * @param prerequisitesCached the Storyteller EPUB bundle and cross-EPUB index are available.
 */
data class BookSyncState(
    val isMatched: Boolean,
    val confirmedAbsLinkCount: Int,
    val prerequisitesCached: Boolean,
)

/**
 * The set of remotes a sync cycle reconciles for the open book.
 *
 * - Unmatched: single-peer `{ABS ebook}`.
 * - Matched, **exactly one** ABS link, prerequisites cached: three-peer.
 * - Matched, **more than one** ABS link: the multi-link guard — those are distinct users'
 *   progress sharing one Storyteller position, so Storyteller is excluded and the book
 *   degrades to a two-peer ABS-ebook ↔ ABS-audiobook cycle.
 * - Matched, one ABS link, prerequisites not yet cached: single-peer `{ABS ebook}` until the
 *   prerequisites land, then it upgrades on a later cycle.
 */
fun applicableRemotes(state: BookSyncState): Set<RemoteKind> {
    if (!state.isMatched) return setOf(RemoteKind.ABS_EBOOK)

    if (state.confirmedAbsLinkCount > 1) {
        // Multi-link guard: never write the shared Storyteller position.
        return setOf(RemoteKind.ABS_EBOOK, RemoteKind.ABS_AUDIO)
    }

    if (!state.prerequisitesCached) return setOf(RemoteKind.ABS_EBOOK)

    return setOf(RemoteKind.ABS_EBOOK, RemoteKind.ABS_AUDIO, RemoteKind.STORYTELLER)
}

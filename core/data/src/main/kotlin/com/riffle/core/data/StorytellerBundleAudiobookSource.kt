package com.riffle.core.data

import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.BundleAudiobookSource
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository

/**
 * The sole [BundleAudiobookSource] implementation, and the only audiobook/library code that knows the
 * offline audio can come from a Storyteller readaloud bundle (ADR 0023). It translates the player's
 * ABS `(sourceId, itemId)` to the bundle's Storyteller `(sourceId, bookId)` via the [ReadaloudLink],
 * then maps the bundle's Media Overlay track to an [AudiobookSession] ([buildBundleAudiobookSession]).
 *
 * [isAvailableOffline] must be synchronous (it backs the library's offline filter), so the suspend
 * link lookup is replaced on that path by an [OfflineAvailabilitySnapshot] — keyed on the ABS
 * `(sourceId, itemId)` pair — kept fresh from the link repository's Flow on the survivable scope.
 */
class StorytellerBundleAudiobookSource(
    private val readaloudLinkRepository: ReadaloudLinkRepository,
    private val readaloudAudioRepository: ReadaloudAudioRepository,
    private val linksByAbsItem: OfflineAvailabilitySnapshot<String, ReadaloudLink>,
) : BundleAudiobookSource {

    override suspend fun localSession(sourceId: String, itemId: String): AudiobookSession? {
        val link = readaloudLinkRepository.findByAbsItem(sourceId, itemId) ?: return null
        val bundle = readaloudAudioRepository.bundleFile(link.storytellerSourceId, link.storytellerBookId)
            ?: return null
        val track = readaloudAudioRepository.readTrack(link.storytellerSourceId, link.storytellerBookId)
            ?: return null
        return buildBundleAudiobookSession(track, bundle)
    }

    override fun isAvailableOffline(sourceId: String, itemId: String): Boolean {
        val link = linksByAbsItem[absItemKey(sourceId, itemId)] ?: return false
        return readaloudAudioRepository.isAudioAvailable(link.storytellerSourceId, link.storytellerBookId)
    }
}

// NUL separator: it can't occur in an ABS server or item id, so the key can't collide across
// different (sourceId, itemId) splits the way a "/" join could. Shared between the source's
// read-side lookup and the snapshot's write-side map builder so both stay in sync.
private val ABS_KEY_SEPARATOR: Char = Char(0)

internal fun absItemKey(sourceId: String, itemId: String) = "$sourceId$ABS_KEY_SEPARATOR$itemId"

/**
 * Builds the [ReadaloudLink] map keyed by the ABS `(sourceId, itemId)` pair — the read-side key the
 * [StorytellerBundleAudiobookSource.isAvailableOffline] check uses.
 */
fun readaloudLinksByAbsItemKey(links: List<ReadaloudLink>): Map<String, ReadaloudLink> =
    links.associateBy { absItemKey(it.absSourceId, it.absLibraryItemId) }

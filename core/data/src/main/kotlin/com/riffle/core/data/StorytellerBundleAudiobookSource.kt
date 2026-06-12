package com.riffle.core.data

import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.BundleAudiobookSource
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The sole [BundleAudiobookSource] implementation, and the only audiobook/library code that knows the
 * offline audio can come from a Storyteller readaloud bundle (ADR 0023). It translates the player's
 * ABS `(serverId, itemId)` to the bundle's Storyteller `(serverId, bookId)` via the [ReadaloudLink],
 * then maps the bundle's Media Overlay track to an [AudiobookSession] ([buildBundleAudiobookSession]).
 *
 * [isAvailableOffline] must be synchronous (it backs the library's offline filter), so the suspend
 * link lookup is replaced on that path by a [linksByAbsItem] snapshot kept fresh from
 * [ReadaloudLinkRepository.observeAll] on an application-lifetime [scope] — the same self-managed IO
 * scope pattern as [CrossEpubIndexBuilderService].
 */
class StorytellerBundleAudiobookSource(
    private val readaloudLinkRepository: ReadaloudLinkRepository,
    private val readaloudAudioRepository: ReadaloudAudioRepository,
    scope: CoroutineScope,
) : BundleAudiobookSource {

    // ABS "serverId/itemId" -> link, so isAvailableOffline is a synchronous lookup.
    @Volatile
    private var linksByAbsItem: Map<String, ReadaloudLink> = emptyMap()

    init {
        scope.launch {
            readaloudLinkRepository.observeAll().collect { links ->
                linksByAbsItem = links.associateBy { absKey(it.absServerId, it.absLibraryItemId) }
            }
        }
    }

    override suspend fun localSession(serverId: String, itemId: String): AudiobookSession? {
        val link = readaloudLinkRepository.findByAbsItem(serverId, itemId) ?: return null
        val bundle = readaloudAudioRepository.bundleFile(link.storytellerServerId, link.storytellerBookId)
            ?: return null
        val track = readaloudAudioRepository.readTrack(link.storytellerServerId, link.storytellerBookId)
            ?: return null
        return buildBundleAudiobookSession(track, bundle)
    }

    override fun isAvailableOffline(serverId: String, itemId: String): Boolean {
        val link = linksByAbsItem[absKey(serverId, itemId)] ?: return false
        return readaloudAudioRepository.isAudioAvailable(link.storytellerServerId, link.storytellerBookId)
    }

    private fun absKey(serverId: String, itemId: String) = "$serverId/$itemId"
}

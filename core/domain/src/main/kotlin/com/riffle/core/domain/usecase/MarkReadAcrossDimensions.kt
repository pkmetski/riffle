package com.riffle.core.domain.usecase

import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.SourceRepository
import javax.inject.Inject

/**
 * Mark a book read or unread across **every** ABS item coupled by the same readaloud bundle — the
 * ebook AND its audiobook counterpart — so the two never disagree (a readaloud's ebook and
 * audiobook are separate ABS items that should track one finished state). Falls back to just the
 * given item when there's no link or no active server.
 *
 * Owns the cross-cutting bug area where a "mark read" that only touched the ebook dimension left
 * the audiobook unfinished and the next sweep restored the old percentage.
 */
open class MarkReadAcrossDimensions @Inject constructor(
    private val libraryMutator: LibraryMutator,
    private val readingSessionRepository: ReadingSessionRepository,
    private val readaloudLinkRepository: ReadaloudLinkRepository,
    private val serverRepository: SourceRepository,
) {
    open suspend operator fun invoke(itemId: String, finished: Boolean) {
        val progress = if (finished) 1.0f else 0.0f
        val serverId = serverRepository.getActive()?.id
        val ids = if (serverId != null) coupledAbsItemIds(serverId, itemId) else listOf(itemId)
        ids.forEach { id ->
            libraryMutator.updateReadingProgress(id, progress)
            readingSessionRepository.markFinished(id, finished)
        }
    }

    /**
     * The set of ABS item ids on the active server that share this item's readaloud bundle (always
     * includes [itemId]). Cross-server matches are excluded — [ReadingSessionRepository.markFinished]
     * operates on the active server only.
     */
    private suspend fun coupledAbsItemIds(serverId: String, itemId: String): List<String> {
        val link = readaloudLinkRepository.findByAbsItem(serverId, itemId) ?: return listOf(itemId)
        val siblings = readaloudLinkRepository
            .findByStorytellerBook(link.storytellerServerId, link.storytellerBookId)
            .filter { it.absServerId == serverId }
            .map { it.absLibraryItemId }
        return (siblings + itemId).distinct()
    }
}

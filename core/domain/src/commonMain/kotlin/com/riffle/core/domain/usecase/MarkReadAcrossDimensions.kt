package com.riffle.core.domain.usecase

import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadingSessionRepository
import com.riffle.core.domain.SourceRepository

/**
 * Mark a book read or unread across **every** ABS item coupled by the same readaloud bundle — the
 * ebook AND its audiobook counterpart — so the two never disagree (a readaloud's ebook and
 * audiobook are separate ABS items that should track one finished state). Falls back to just the
 * given item when there is no link.
 *
 * [sourceId] must be the item's own source ID, not the currently-active source. This is required
 * so web-source items (radio.es, Gutenberg, etc.) are written to the correct `library_items` row
 * even when an ABS server is active at the same time.
 *
 * Owns the cross-cutting bug area where a "mark read" that only touched the ebook dimension left
 * the audiobook unfinished and the next sweep restored the old percentage.
 */
open class MarkReadAcrossDimensions constructor(
    private val libraryMutator: LibraryMutator,
    private val readingSessionRepository: ReadingSessionRepository,
    private val readaloudLinkRepository: ReadaloudLinkRepository,
    private val sourceRepository: SourceRepository,
) {
    open suspend operator fun invoke(sourceId: String, itemId: String, finished: Boolean) {
        val progress = if (finished) 1.0f else 0.0f
        val ids = coupledAbsItemIds(sourceId, itemId)
        ids.forEach { id ->
            libraryMutator.updateReadingProgress(sourceId, id, progress)
            readingSessionRepository.markFinished(sourceId, id, finished)
        }
    }

    /**
     * The set of ABS item ids on [sourceId]'s server that share this item's readaloud bundle
     * (always includes [itemId]). Cross-server matches are excluded — [ReadingSessionRepository.markFinished]
     * operates on the given source only.
     */
    private suspend fun coupledAbsItemIds(sourceId: String, itemId: String): List<String> {
        val link = readaloudLinkRepository.findByAbsItem(sourceId, itemId) ?: return listOf(itemId)
        val siblings = readaloudLinkRepository
            .findByStorytellerBook(link.storytellerSourceId, link.storytellerBookId)
            .filter { it.absSourceId == sourceId }
            .map { it.absLibraryItemId }
        return (siblings + itemId).distinct()
    }
}

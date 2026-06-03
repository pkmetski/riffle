package com.riffle.app.feature.reader

import com.riffle.core.data.di.EpubCacheStore
import com.riffle.core.data.di.EpubDownloadsStore
import com.riffle.core.domain.BookSyncState
import com.riffle.core.domain.CanonicalPositionTranslator
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.EpubChecksum
import com.riffle.core.domain.EpubContentExtractor
import com.riffle.core.domain.ExtractedEpub
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.OpenedSide
import com.riffle.core.domain.ReadaloudLink
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.ServerType
import com.riffle.core.domain.StorytellerFragmentIndexBuilder
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.StorytellerPositionApi
import javax.inject.Inject

/**
 * Assembles a [ThreePeerReaderSyncCoordinator] for the open book when, and only when, all of
 * ADR 0019's three-peer prerequisites are met: the book is a Confirmed match with **exactly
 * one** ABS link, both EPUBs are cached, and the cross-EPUB index for their current checksums
 * is built. Any miss returns `null`, leaving the reader on its existing single-peer path — so
 * three-peer is strictly additive and never degrades the non-matched case.
 */
class ThreePeerReaderSyncFactory @Inject constructor(
    private val linkRepository: ReadaloudLinkRepository,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val indexStore: CrossEpubIndexStore,
    private val absSessionApi: AbsSessionApi,
    private val storytellerPositionApi: StorytellerPositionApi,
    @EpubCacheStore private val cacheStore: LocalStore,
    @EpubDownloadsStore private val downloadsStore: LocalStore,
) {
    /**
     * @param itemId the id the reader opened (an ABS Library Item id on the ABS side, a
     *   Storyteller book id on the Readaloud side).
     */
    suspend fun createIfApplicable(itemId: String): ThreePeerReaderSyncCoordinator? {
        val active = serverRepository.getActive() ?: return null
        val side = if (active.serverType == ServerType.STORYTELLER) OpenedSide.READALOUD else OpenedSide.ABS

        // Resolve the single Confirmed link; three-peer requires exactly one ABS link.
        val link = when (side) {
            OpenedSide.ABS -> linkRepository.findByAbsItem(active.id, itemId) ?: return null
            OpenedSide.READALOUD -> linkRepository.findByStorytellerBook(active.id, itemId).singleOrNull() ?: return null
        }
        val absLinkCount = linkRepository.findByStorytellerBook(link.storytellerServerId, link.storytellerBookId).size
        if (absLinkCount != 1) return null

        val absServer = serverRepository.getById(link.absServerId) ?: return null
        val absToken = tokenStorage.getToken(link.absServerId) ?: return null
        val storytellerServer = serverRepository.getById(link.storytellerServerId) ?: return null
        val storytellerToken = tokenStorage.getToken(link.storytellerServerId) ?: return null

        val absFile = cachedFile(link.absLibraryItemId) ?: return null
        val storytellerFile = cachedFile(link.storytellerBookId) ?: return null

        // The index must already be built for these exact bytes (checksum-keyed); otherwise the
        // background builder hasn't caught up — stay single-peer until it has. Checksums stream the
        // file (the synced bundle is hundreds of MB — never read it into memory; ADR 0023).
        val index = indexStore.load(EpubChecksum.of(absFile), EpubChecksum.of(storytellerFile)) ?: return null

        val absExtract = EpubContentExtractor.extract(absFile) ?: return null
        val storytellerExtract = EpubContentExtractor.extract(storytellerFile) ?: return null

        val fragmentProgressions = StorytellerFragmentIndexBuilder.build(
            storytellerExtract.chapters, storytellerExtract.smilClips,
        )
        val translator = CanonicalPositionTranslator(storytellerExtract.smilClips, index, fragmentProgressions)

        val bridge = ReaderPositionBridge(
            displayedSide = side,
            absSpineHrefs = absExtract.hrefs(),
            absChapterHtml = absExtract.htmlAt(),
            storytellerSpineHrefs = storytellerExtract.hrefs(),
            storytellerChapterHtml = storytellerExtract.htmlAt(),
            translator = translator,
        )

        return ThreePeerReaderSyncCoordinator(
            state = BookSyncState(isMatched = true, confirmedAbsLinkCount = 1, prerequisitesCached = true, openedSide = side),
            bridge = bridge,
            absApi = absSessionApi,
            storytellerApi = storytellerPositionApi,
            absEndpoint = endpoint(absServer, absToken, link.absLibraryItemId),
            storytellerEndpoint = StorytellerSyncEndpoint(
                storytellerServer.url.value, storytellerToken, storytellerServer.insecureConnectionAllowed, link.storytellerBookId,
            ),
        )
    }

    private fun endpoint(server: Server, token: String, itemId: String) =
        AbsSyncEndpoint(server.url.value, token, server.insecureConnectionAllowed, itemId)

    private fun cachedFile(itemId: String): java.io.File? =
        downloadsStore.get(itemId) ?: cacheStore.get(itemId)

    private fun ExtractedEpub.hrefs() = chapters.map { it.href }
    private fun ExtractedEpub.htmlAt(): (Int) -> String? = { chapters.getOrNull(it)?.html }
}

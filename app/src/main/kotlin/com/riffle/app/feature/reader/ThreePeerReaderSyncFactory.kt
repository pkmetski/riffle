package com.riffle.app.feature.reader

import com.riffle.core.data.di.EpubCacheStore
import com.riffle.core.data.di.EpubDownloadsStore
import com.riffle.core.domain.BookSyncState
import com.riffle.core.domain.CanonicalPositionTranslator
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.EpubChecksum
import com.riffle.core.domain.EpubContentExtractor
import com.riffle.core.domain.ExtractedEpub
import com.riffle.core.domain.LibraryRepository
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ServerRepository
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
    private val libraryRepository: LibraryRepository,
    @EpubCacheStore private val cacheStore: LocalStore,
    @EpubDownloadsStore private val downloadsStore: LocalStore,
) {
    /**
     * @param itemId the ABS Library Item id the reader opened. A book is always read from the ABS
     *   side (ADR 0026), so the link is resolved by ABS item.
     */
    suspend fun createIfApplicable(itemId: String): ThreePeerReaderSyncCoordinator? {
        val active = serverRepository.getActive() ?: return null

        // The readaloud bundle is the hub: route progress to whichever ABS items are matched to it.
        val openedLink = linkRepository.findByAbsItem(active.id, itemId) ?: return null
        val allLinks = linkRepository.findByStorytellerBook(openedLink.storytellerServerId, openedLink.storytellerBookId)
        val linkedMedia = allLinks.mapNotNull { l ->
            val item = libraryRepository.getItem(l.absServerId, l.absLibraryItemId) ?: return@mapNotNull null
            AbsLinkMedia(l, isSupported = item.isSupported, hasAudio = item.hasAudio)
        }
        val targets = resolveAbsTargets(itemId, linkedMedia)
        // The reader displays the ABS EPUB (ADR 0026): no matched ebook item ⇒ nothing to display
        // and no frame for the cross-EPUB index, so three-peer can't apply.
        val ebookLink = targets.ebook ?: return null

        val storytellerServer = serverRepository.getById(openedLink.storytellerServerId) ?: return null
        val storytellerToken = tokenStorage.getToken(openedLink.storytellerServerId) ?: return null

        val absFile = cachedFile(ebookLink.absServerId, ebookLink.absLibraryItemId) ?: return null
        val storytellerFile = cachedFile(openedLink.storytellerServerId, openedLink.storytellerBookId) ?: return null

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
            absSpineHrefs = absExtract.hrefs(),
            absChapterHtml = absExtract.htmlAt(),
            storytellerSpineHrefs = storytellerExtract.hrefs(),
            storytellerChapterHtml = storytellerExtract.htmlAt(),
            translator = translator,
        )

        val absEbookEndpoint = absEndpointFor(ebookLink.absServerId, ebookLink.absLibraryItemId)
        val absAudioEndpoint = targets.audio?.let { absEndpointFor(it.absServerId, it.absLibraryItemId) }

        return ThreePeerReaderSyncCoordinator(
            state = BookSyncState(
                isMatched = true,
                hasAbsEbookTarget = absEbookEndpoint != null,
                hasAbsAudioTarget = absAudioEndpoint != null,
                prerequisitesCached = true,
            ),
            bridge = bridge,
            absApi = absSessionApi,
            storytellerApi = storytellerPositionApi,
            absEbookEndpoint = absEbookEndpoint,
            absAudioEndpoint = absAudioEndpoint,
            storytellerEndpoint = StorytellerSyncEndpoint(
                storytellerServer.url.value, storytellerToken, storytellerServer.insecureConnectionAllowed, openedLink.storytellerBookId,
            ),
        )
    }

    private suspend fun absEndpointFor(serverId: String, itemId: String): AbsSyncEndpoint? {
        val server = serverRepository.getById(serverId) ?: return null
        val token = tokenStorage.getToken(serverId) ?: return null
        return AbsSyncEndpoint(server.url.value, token, server.insecureConnectionAllowed, itemId)
    }

    private fun cachedFile(serverId: String, itemId: String): java.io.File? =
        downloadsStore.get(serverId, itemId) ?: cacheStore.get(serverId, itemId)

    private fun ExtractedEpub.hrefs() = chapters.map { it.href }
    private fun ExtractedEpub.htmlAt(): (Int) -> String? = { chapters.getOrNull(it)?.html }
}

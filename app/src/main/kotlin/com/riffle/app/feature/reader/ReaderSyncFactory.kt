package com.riffle.app.feature.reader

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.data.CrossEpubIndexBuildTrigger
import com.riffle.core.data.di.EpubCacheStore
import com.riffle.core.data.di.EpubDownloadsStore
import com.riffle.core.domain.BookSyncState
import com.riffle.core.domain.Clock
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.DefaultPositionTranslator
import com.riffle.core.domain.EpubChecksum
import com.riffle.core.domain.EpubContentExtractor
import com.riffle.core.domain.ExtractedEpub
import com.riffle.core.domain.LibraryObserver
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudSidecarCache
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.StorytellerFragmentIndexBuilder
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import javax.inject.Inject

open class ReaderSyncFactory @Inject constructor(
    private val linkRepository: ReadaloudLinkRepository,
    private val sourceRepository: SourceRepository,
    private val catalogRegistry: CatalogRegistry,
    private val indexStore: CrossEpubIndexStore,
    private val libraryObserver: LibraryObserver,
    @EpubCacheStore private val cacheStore: LocalStore,
    @EpubDownloadsStore private val downloadsStore: LocalStore,
    private val crossEpubIndexBuildTrigger: CrossEpubIndexBuildTrigger,
    private val sidecarCache: ReadaloudSidecarCache,
    private val clock: Clock,
    private val logger: Logger,
) {
    open suspend fun createIfApplicable(itemId: String): ReaderSyncCoordinator? {
        val active = sourceRepository.getActive() ?: return null

        val openedLink = linkRepository.findByAbsItem(active.id, itemId) ?: return null
        val allLinks = linkRepository.findByStorytellerBook(openedLink.storytellerSourceId, openedLink.storytellerBookId)
        val linkedMedia = allLinks.mapNotNull { l ->
            val item = libraryObserver.getItem(l.absSourceId, l.absLibraryItemId) ?: return@mapNotNull null
            AbsLinkMedia(l, isReadable = item.isReadable, hasAudio = item.hasAudio, audioDurationSec = item.audioDurationSec)
        }
        val targets = resolveAbsTargets(itemId, linkedMedia)
        val ebookLink = targets.ebook ?: return null

        val absFile = cachedFile(ebookLink.absSourceId, ebookLink.absLibraryItemId) ?: return null
        val storytellerFile = cachedFile(openedLink.storytellerSourceId, openedLink.storytellerBookId) ?: return null

        val index = indexStore.load(EpubChecksum.of(absFile), EpubChecksum.of(storytellerFile)) ?: run {
            logger.w(LogChannel.Readaloud) {
                "cross-EPUB index missing for matched item $itemId (ebook=${ebookLink.absLibraryItemId}); " +
                    "position sync degraded to single-peer — enqueued a build"
            }
            crossEpubIndexBuildTrigger.enqueueBuild(openedLink)
            return null
        }

        val absExtract = EpubContentExtractor.extract(absFile) ?: return null
        val storytellerExtract = EpubContentExtractor.extract(storytellerFile) ?: return null

        val fragmentProgressions = StorytellerFragmentIndexBuilder.build(
            storytellerExtract.chapters, storytellerExtract.smilClips,
        )
        val translator = DefaultPositionTranslator(
            smilClips = storytellerExtract.smilClips,
            crossEpubIndex = index,
            fragmentProgressions = fragmentProgressions,
            absSpineHrefs = absExtract.hrefs(),
            absChapterHtml = absExtract.htmlAt(),
            storytellerSpineHrefs = storytellerExtract.hrefs(),
            storytellerChapterHtml = storytellerExtract.htmlAt(),
        )

        val absEbookEndpoint = ebookEndpointFor(ebookLink.absSourceId, ebookLink.absLibraryItemId)
        val absAudioEndpoint = targets.audio?.let { a ->
            val durationSec = linkedMedia.firstOrNull { it.link.absLibraryItemId == a.absLibraryItemId }?.audioDurationSec ?: 0.0
            audioEndpointFor(a.absSourceId, a.absLibraryItemId, durationSec)
        }

        return ReaderSyncCoordinator(
            state = BookSyncState(
                isMatched = true,
                hasEbookPeer = absEbookEndpoint != null,
                hasAudioPeer = absAudioEndpoint != null,
                prerequisitesCached = true,
            ),
            translator = translator,
            clock = clock,
            ebookEndpoint = absEbookEndpoint,
            audioEndpoint = absAudioEndpoint,
        )
    }

    open suspend fun createAudiobookFollowIfApplicable(itemId: String): AudiobookFollow? {
        val active = sourceRepository.getActive() ?: return null
        val openedLink = linkRepository.findByAbsItem(active.id, itemId) ?: return null
        val allLinks = linkRepository.findByStorytellerBook(openedLink.storytellerSourceId, openedLink.storytellerBookId)
        val linkedMedia = allLinks.mapNotNull { l ->
            val item = libraryObserver.getItem(l.absSourceId, l.absLibraryItemId) ?: return@mapNotNull null
            AbsLinkMedia(l, isReadable = item.isReadable, hasAudio = item.hasAudio, audioDurationSec = item.audioDurationSec)
        }
        val targets = resolveAbsTargets(itemId, linkedMedia)
        val audioTarget = targets.audio ?: return null
        val storytellerFile = cachedFile(openedLink.storytellerSourceId, openedLink.storytellerBookId)
            ?: sidecarCache.cachedFile(openedLink.storytellerSourceId, openedLink.storytellerBookId)
            ?: return null
        val storytellerExtract = EpubContentExtractor.extract(storytellerFile) ?: return null
        val durationSec = linkedMedia.firstOrNull { it.link.absLibraryItemId == audioTarget.absLibraryItemId }?.audioDurationSec ?: 0.0
        val endpoint = audioEndpointFor(audioTarget.absSourceId, audioTarget.absLibraryItemId, durationSec) ?: return null
        return AudiobookFollow(
            endpoint = endpoint,
            translator = DefaultPositionTranslator(smilClips = storytellerExtract.smilClips),
            clock = clock,
            sourceId = audioTarget.absSourceId,
            audioItemId = audioTarget.absLibraryItemId,
            ebookItemId = targets.ebook?.absLibraryItemId,
            quotes = com.riffle.core.domain.ReadaloudTextQuotes.build(storytellerExtract.chapters),
        )
    }

    private suspend fun ebookEndpointFor(sourceId: String, itemId: String): CatalogEbookEndpoint? {
        val peer = catalogRegistry.forSourceId(sourceId) as? ProgressPeerCapability ?: return null
        return CatalogEbookEndpoint(peer, itemId)
    }

    private suspend fun audioEndpointFor(sourceId: String, itemId: String, durationSec: Double): CatalogAudioEndpoint? {
        val catalog = catalogRegistry.forSourceId(sourceId) ?: return null
        val peer = catalog as? ProgressPeerCapability ?: return null
        val audioPeer = catalog as? AudiobookProgressPeerCapability ?: return null
        return CatalogAudioEndpoint(peer, audioPeer, itemId, durationSec)
    }

    private fun cachedFile(sourceId: String, itemId: String): java.io.File? =
        downloadsStore.get(sourceId, itemId) ?: cacheStore.get(sourceId, itemId)

    private fun ExtractedEpub.hrefs() = chapters.map { it.href }
    private fun ExtractedEpub.htmlAt(): (Int) -> String? = { chapters.getOrNull(it)?.html }
}

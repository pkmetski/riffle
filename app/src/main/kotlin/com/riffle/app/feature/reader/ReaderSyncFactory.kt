package com.riffle.app.feature.reader

import com.riffle.core.data.CrossEpubIndexBuildTrigger
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
import com.riffle.core.domain.ReadaloudSidecarCache
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.StorytellerFragmentIndexBuilder
import com.riffle.core.domain.TokenStorage
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.network.AbsSessionApi
import javax.inject.Inject

/**
 * Assembles a [ReaderSyncCoordinator] for the open book when, and only when, all of
 * ADR 0019's reconciliation prerequisites are met: the book is a Confirmed match with **exactly
 * one** ABS link, both EPUBs are cached, and the cross-EPUB index for their current checksums
 * is built. Any miss returns `null`, leaving the reader on its existing single-peer path — so
 * the reconciliation cycle is strictly additive and never degrades the non-matched case.
 */
open class ReaderSyncFactory @Inject constructor(
    private val linkRepository: ReadaloudLinkRepository,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val indexStore: CrossEpubIndexStore,
    private val absSessionApi: AbsSessionApi,
    private val libraryRepository: LibraryRepository,
    @EpubCacheStore private val cacheStore: LocalStore,
    @EpubDownloadsStore private val downloadsStore: LocalStore,
    private val crossEpubIndexBuildTrigger: CrossEpubIndexBuildTrigger,
    private val sidecarCache: ReadaloudSidecarCache,
    private val logger: Logger,
) {
    /**
     * @param itemId the ABS Library Item id the reader opened. A book is always read from the ABS
     *   side (ADR 0026), so the link is resolved by ABS item.
     */
    open suspend fun createIfApplicable(itemId: String): ReaderSyncCoordinator? {
        val active = serverRepository.getActive() ?: return null

        // The readaloud bundle is the hub: route progress to whichever ABS items are matched to it.
        val openedLink = linkRepository.findByAbsItem(active.id, itemId) ?: return null
        val allLinks = linkRepository.findByStorytellerBook(openedLink.storytellerServerId, openedLink.storytellerBookId)
        val linkedMedia = allLinks.mapNotNull { l ->
            val item = libraryRepository.getItem(l.absServerId, l.absLibraryItemId) ?: return@mapNotNull null
            AbsLinkMedia(l, isReadable = item.isReadable, hasAudio = item.hasAudio, audioDurationSec = item.audioDurationSec)
        }
        val targets = resolveAbsTargets(itemId, linkedMedia)
        // The reader displays the ABS EPUB (ADR 0026): no matched ebook item ⇒ nothing to display
        // and no frame for the cross-EPUB index, so the reconciliation cycle can't apply.
        val ebookLink = targets.ebook ?: return null

        val absFile = cachedFile(ebookLink.absServerId, ebookLink.absLibraryItemId) ?: return null
        val storytellerFile = cachedFile(openedLink.storytellerServerId, openedLink.storytellerBookId) ?: return null

        // The index must already be built for these exact bytes (checksum-keyed); otherwise the
        // background builder hasn't caught up — stay single-peer until it has. Checksums stream the
        // file (the synced bundle is hundreds of MB — never read it into memory; ADR 0023).
        val index = indexStore.load(EpubChecksum.of(absFile), EpubChecksum.of(storytellerFile)) ?: run {
            // An operation requiring the cross-EPUB index, reached before the index exists. Both EPUBs
            // are cached (above), so the build's prerequisites are all present — self-heal by enqueueing
            // it (idempotent), and leave a trace explaining why this book's position sync just degraded
            // to single-peer. This is the reader-open AND player-open retry path in one place; it also
            // catches a deferred/failed download-time build, an EPUB re-upload (checksum change), or a
            // bundle present from outside the in-app download flow (ADR 0031).
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
        val translator = CanonicalPositionTranslator(storytellerExtract.smilClips, index, fragmentProgressions)

        val bridge = ReaderPositionBridge(
            absSpineHrefs = absExtract.hrefs(),
            absChapterHtml = absExtract.htmlAt(),
            storytellerSpineHrefs = storytellerExtract.hrefs(),
            storytellerChapterHtml = storytellerExtract.htmlAt(),
            translator = translator,
        )

        val absEbookEndpoint = absEndpointFor(ebookLink.absServerId, ebookLink.absLibraryItemId)
        val absAudioEndpoint = targets.audio?.let { a ->
            val durationSec = linkedMedia.firstOrNull { it.link.absLibraryItemId == a.absLibraryItemId }?.audioDurationSec ?: 0.0
            absEndpointFor(a.absServerId, a.absLibraryItemId, durationSec)
        }

        return ReaderSyncCoordinator(
            state = BookSyncState(
                isMatched = true,
                hasAbsEbookTarget = absEbookEndpoint != null,
                hasAbsAudioTarget = absAudioEndpoint != null,
                prerequisitesCached = true,
            ),
            bridge = bridge,
            absApi = absSessionApi,
            absEbookEndpoint = absEbookEndpoint,
            absAudioEndpoint = absAudioEndpoint,
        )
    }

    /**
     * Builds a bundle-SMIL-only [AudiobookFollow] for a matched book that has an audiobook target and a
     * cached Storyteller bundle — **without** requiring the ABS EPUB or the cross-EPUB index that
     * [createIfApplicable] needs (ADR 0031). Lets readaloud sync to the audiobook in the window before
     * the index is built (or when it can't be). `null` when there is no audio target or no cached bundle.
     */
    open suspend fun createAudiobookFollowIfApplicable(itemId: String): AudiobookFollow? {
        val active = serverRepository.getActive() ?: return null
        val openedLink = linkRepository.findByAbsItem(active.id, itemId) ?: return null
        val allLinks = linkRepository.findByStorytellerBook(openedLink.storytellerServerId, openedLink.storytellerBookId)
        val linkedMedia = allLinks.mapNotNull { l ->
            val item = libraryRepository.getItem(l.absServerId, l.absLibraryItemId) ?: return@mapNotNull null
            AbsLinkMedia(l, isReadable = item.isReadable, hasAudio = item.hasAudio, audioDurationSec = item.audioDurationSec)
        }
        val targets = resolveAbsTargets(itemId, linkedMedia)
        val audioTarget = targets.audio ?: return null
        // For the streaming path (ADR 0028) the sidecar stands in for the full bundle: it carries the
        // SMIL clips and chapter HTML needed by CanonicalPositionTranslator and ReadaloudTextQuotes, so
        // AudiobookFollow can be built without a downloaded bundle. createIfApplicable() still requires
        // the full bundle (for cross-EPUB index checksums), so the coordinator stays on the sidecar-only
        // path until the bundle is downloaded.
        val storytellerFile = cachedFile(openedLink.storytellerServerId, openedLink.storytellerBookId)
            ?: sidecarCache.cachedFile(openedLink.storytellerServerId, openedLink.storytellerBookId)
            ?: return null
        val storytellerExtract = EpubContentExtractor.extract(storytellerFile) ?: return null
        val durationSec = linkedMedia.firstOrNull { it.link.absLibraryItemId == audioTarget.absLibraryItemId }?.audioDurationSec ?: 0.0
        val endpoint = absEndpointFor(audioTarget.absServerId, audioTarget.absLibraryItemId, durationSec) ?: return null
        return AudiobookFollow(
            absApi = absSessionApi,
            endpoint = endpoint,
            translator = CanonicalPositionTranslator(storytellerExtract.smilClips),
            serverId = audioTarget.absServerId,
            audioItemId = audioTarget.absLibraryItemId,
            // The ebook target + the bundle's sentence quotes let audiobook→ebook resolve index-free,
            // text-anchored on the ABS EPUB (ADR 0031).
            ebookItemId = targets.ebook?.absLibraryItemId,
            quotes = com.riffle.core.domain.ReadaloudTextQuotes.build(storytellerExtract.chapters),
        )
    }

    private suspend fun absEndpointFor(serverId: String, itemId: String, durationSec: Double = 0.0): AbsSyncEndpoint? {
        val server = serverRepository.getById(serverId) ?: return null
        val token = tokenStorage.getToken(serverId) ?: return null
        return AbsSyncEndpoint(server.url.value, token, server.insecureConnectionAllowed, itemId, durationSec)
    }

    private fun cachedFile(serverId: String, itemId: String): java.io.File? =
        downloadsStore.get(serverId, itemId) ?: cacheStore.get(serverId, itemId)

    private fun ExtractedEpub.hrefs() = chapters.map { it.href }
    private fun ExtractedEpub.htmlAt(): (Int) -> String? = { chapters.getOrNull(it)?.html }
}

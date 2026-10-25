package com.riffle.shared.readaloud

import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.data.AudiobookIdentityResolver
import com.riffle.core.data.IosAudiobookFiles
import com.riffle.core.data.IosMediaOverlayReader
import com.riffle.core.data.IosReadaloudSidecarStore
import com.riffle.core.data.StreamingMediaPlan
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.SegmentTrackMapper
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.AudiobookFingerprint
import com.riffle.core.models.AudiobookIdentityResult
import com.riffle.core.network.NetworkAbsAudioTrack
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.StorytellerLibraryApi

/**
 * iOS equivalent of Android's `ReadaloudStreamingSessionFactory` (ADR 0040).
 *
 * Resolves the linked ABS audiobook, verifies its recording identity against Storyteller's
 * fingerprint, then builds an [IosReadaloudStreamingSession] from the cached sidecar and the ABS
 * track URLs — so [IosReadaloudPlayerController] can stream audio directly from ABS without
 * extracting audio from a local zip.
 */
internal class IosReadaloudStreamingSessionFactory(
    private val audioIdentityResolver: AudioIdentityResolver,
    private val catalogRegistry: CatalogRegistry,
    private val storytellerApi: StorytellerLibraryApi,
    private val sidecarStore: IosReadaloudSidecarStore,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val linkRepository: ReadaloudLinkRepository,
) {
    private val evictedPartialSidecars = mutableSetOf<String>()

    suspend fun tryBuild(storytellerSourceId: String, storytellerBookId: String): IosReadaloudStreamingSession? {
        val audiobook = audioIdentityResolver.resolveForStorytellerBook(storytellerSourceId, storytellerBookId)
        if (audiobook.sourceId == storytellerSourceId && audiobook.bookId == storytellerBookId) return null

        val absSource = sourceRepository.getById(audiobook.sourceId) ?: return null
        val absToken = tokenStorage.getToken(audiobook.sourceId) ?: return null
        val catalog = catalogRegistry.forSourceId(audiobook.sourceId) ?: return null
        val audioCap = catalog as? AudiobookMediaCapability ?: return null

        val persisted = linkRepository.findByAbsItem(audiobook.sourceId, audiobook.bookId)?.identityResult
        if (persisted != null && persisted != AudiobookIdentityResult.UNKNOWN) {
            if (persisted != AudiobookIdentityResult.VERIFIED) return null
        } else {
            val stSource = sourceRepository.getById(storytellerSourceId) ?: return null
            val stToken = tokenStorage.getToken(storytellerSourceId) ?: return null
            val bookId = storytellerBookId.toLongOrNull() ?: return null
            val stFpRes = storytellerApi.getAudiobookFingerprint(stSource.url.value, bookId, stToken, stSource.insecureConnectionAllowed)
            val stFingerprint: Result<AudiobookFingerprint?> = when (stFpRes) {
                is NetworkResult.Success -> Result.success(stFpRes.value)
                else -> Result.failure(RuntimeException("storyteller fingerprint fetch failed"))
            }
            val absFingerprint: Result<AudiobookFingerprint?> = runCatching {
                audioCap.getFingerprint(audiobook.bookId)?.let { fp ->
                    AudiobookFingerprint(fp.fileSizeBytes, fp.totalDurationSec, fp.trackDurations)
                }
            }
            val verdict = AudiobookIdentityResolver.resolve(stFingerprint, absFingerprint)
            linkRepository.updateIdentityResult(audiobook.sourceId, audiobook.bookId, verdict)
            if (verdict != AudiobookIdentityResult.VERIFIED) return null
        }

        val tracks = runCatching { audioCap.getTracks(audiobook.bookId) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null

        val sidecarPath = sidecarStore.cachedSidecarPath(storytellerSourceId, storytellerBookId) ?: return null
        val bytes = IosAudiobookFiles.readBytes(sidecarPath) ?: return null

        val track = runCatching { IosMediaOverlayReader.readTrack(bytes) }.getOrNull()
            ?.takeIf { it.clips.isNotEmpty() } ?: return null

        val networkTracks = tracks.map { t -> NetworkAbsAudioTrack(ino = t.ino, index = t.index, durationSec = t.durationSec) }
        val map = SegmentTrackMapper.align(track.clips, networkTracks.map { it.durationSec }) ?: run {
            evictStaleOrPartialSidecar(storytellerSourceId, storytellerBookId, track, tracks.sumOf { it.durationSec })
            return null
        }

        val items = StreamingMediaPlan.build(map.segmentPlacements(), networkTracks, absSource.url.value, audiobook.bookId, absToken)
        return IosReadaloudStreamingSession(track, sidecarPath, items)
    }

    private fun evictStaleOrPartialSidecar(
        sourceId: String,
        bookId: String,
        sidecarTrack: com.riffle.core.domain.ReadaloudTrack,
        absTotalSec: Double,
    ) {
        val clips = sidecarTrack.clips
        val segments = clips.groupBy { it.audioSrc }
        val segTotal = if (segments.isNotEmpty()) segments.values.sumOf { group -> group.maxOf { it.clipEndSec } } else -1.0
        val isPartial = segTotal >= 0.0 && absTotalSec - segTotal > 10.0 * segments.size
        if (clips.isEmpty() || isPartial) {
            val bookKey = sidecarStore.key(sourceId, bookId)
            if (evictedPartialSidecars.add(bookKey)) {
                sidecarStore.remove(sourceId, bookId)
                sidecarStore.prepare(sourceId, bookId)
            }
        }
    }
}

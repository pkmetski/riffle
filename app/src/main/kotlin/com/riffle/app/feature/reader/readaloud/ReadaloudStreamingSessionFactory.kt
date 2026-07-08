package com.riffle.app.feature.reader.readaloud

import android.content.Context
import com.riffle.core.catalog.AudiobookMediaCapability
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.data.AudiobookIdentityResolver
import com.riffle.core.data.MediaOverlayReader
import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.core.data.StreamingSetupBuilder
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudiobookFingerprint
import com.riffle.core.domain.AudiobookIdentityResult
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.StorytellerLibraryApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Assembles a streaming Readaloud session (ADR 0028) for a matched book, or returns null so the
 * caller falls back to the bundle. Streaming is built only when a Source-side audiobook is linked
 * AND its recording identity is VERIFIED against Storyteller's ingested source — so a mismatch
 * never streams.
 */
class ReadaloudStreamingSessionFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioIdentityResolver: AudioIdentityResolver,
    private val catalogRegistry: CatalogRegistry,
    private val storytellerApi: StorytellerLibraryApi,
    private val sidecarStore: ReadaloudSidecarStore,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val linkRepository: ReadaloudLinkRepository,
    private val dispatchers: DispatcherProvider,
) {
    private val evictedPartialSidecars = mutableSetOf<String>()

    data class Session(
        val track: ReadaloudTrack,
        val streaming: SharedBundle.Streaming,
        val sidecarFile: File,
        val absToken: String,
    )

    suspend fun tryBuild(storytellerSourceId: String, storytellerBookId: String): Session? = withContext(dispatchers.io) {
        val audiobook = audioIdentityResolver.resolveForStorytellerBook(storytellerSourceId, storytellerBookId)
        if (audiobook.sourceId == storytellerSourceId && audiobook.bookId == storytellerBookId) {
            return@withContext null
        }

        val absServer = sourceRepository.getById(audiobook.sourceId) ?: return@withContext null
        val absToken = tokenStorage.getToken(audiobook.sourceId) ?: return@withContext null
        val catalog = catalogRegistry.forSourceId(audiobook.sourceId) ?: return@withContext null
        val audioCap = catalog as? AudiobookMediaCapability ?: return@withContext null

        val persisted = linkRepository.findByAbsItem(audiobook.sourceId, audiobook.bookId)?.identityResult
        if (persisted != null && persisted != AudiobookIdentityResult.UNKNOWN) {
            if (persisted != AudiobookIdentityResult.VERIFIED) return@withContext null
        } else {
            val stServer = sourceRepository.getById(storytellerSourceId) ?: return@withContext null
            val stToken = tokenStorage.getToken(storytellerSourceId) ?: return@withContext null
            val bookId = storytellerBookId.toLongOrNull() ?: return@withContext null
            val stFpRes = storytellerApi.getAudiobookFingerprint(stServer.url.value, bookId, stToken, stServer.insecureConnectionAllowed)
            val stFingerprint: Result<AudiobookFingerprint?> = when (stFpRes) {
                is NetworkResult.Success -> Result.success(stFpRes.value)
                else -> Result.failure(RuntimeException("storyteller fingerprint fetch failed"))
            }
            val absFingerprint: Result<AudiobookFingerprint?> = runCatching {
                val fp = audioCap.getFingerprint(audiobook.bookId)
                AudiobookFingerprint(
                    fileSizeBytes = fp.fileSizeBytes,
                    durationSec = fp.totalDurationSec,
                    trackDurationsSec = fp.trackDurations,
                )
            }
            val verdict = AudiobookIdentityResolver.resolve(stFingerprint, absFingerprint)
            linkRepository.updateIdentityResult(audiobook.sourceId, audiobook.bookId, verdict)
            if (verdict != AudiobookIdentityResult.VERIFIED) return@withContext null
        }

        val tracks = runCatching { audioCap.getTracks(audiobook.bookId) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return@withContext null

        val sidecarFile = sidecarStore.cachedFile(storytellerSourceId, storytellerBookId)
            ?: return@withContext null

        val networkTracks = tracks.map { t ->
            com.riffle.core.network.NetworkAbsAudioTrack(
                ino = t.ino,
                index = t.index,
                durationSec = t.durationSec,
            )
        }
        val setup = StreamingSetupBuilder().build(sidecarFile, networkTracks, absServer.url.value, audiobook.bookId, absToken)
        if (setup == null) {
            evictStaleOrPartialSidecar(storytellerSourceId, storytellerBookId, sidecarFile, tracks.sumOf { it.durationSec })
            return@withContext null
        }

        Session(setup.track, SharedBundle.Streaming(setup.items.associateBy { it.audioSrc }), sidecarFile, absToken)
    }

    private fun evictStaleOrPartialSidecar(sourceId: String, bookId: String, sidecarFile: File, absTotalSec: Double) {
        val sidecarTrack = runCatching { MediaOverlayReader.readTrack(sidecarFile) }.getOrNull() ?: return
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

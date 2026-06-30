package com.riffle.app.feature.reader.readaloud

import android.content.Context
import com.riffle.core.data.AudiobookIdentityResolver
import com.riffle.core.data.MediaOverlayReader
import com.riffle.core.data.ReadaloudSidecarStore
import com.riffle.core.data.StreamingSetupBuilder
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudiobookIdentityResult
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkResult
import com.riffle.core.network.StorytellerLibraryApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Assembles a streaming Readaloud session (ADR 0028) for a matched book, or returns null so the
 * caller falls back to the bundle. Streaming is built only when an ABS audiobook is linked AND its
 * recording identity is VERIFIED against Storyteller's ingested source — so a mismatch never streams.
 */
class ReadaloudStreamingSessionFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioIdentityResolver: AudioIdentityResolver,
    private val absApi: AbsLibraryApi,
    private val storytellerApi: StorytellerLibraryApi,
    private val sidecarStore: ReadaloudSidecarStore,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
    private val linkRepository: ReadaloudLinkRepository,
) {
    // Guard against an evict-and-refetch loop when the Storyteller bundle is still partially aligned:
    // we attempt one fresh fetch per session, but if the re-fetched sidecar is also partial we stop.
    private val evictedPartialSidecars = mutableSetOf<String>()

    /**
     * [sidecarFile] stands in for the bundle for the track, highlight quotes, and cross-EPUB index;
     * [absToken] lets the caller eager-complete the audio download while playing (ADR 0028).
     */
    data class Session(
        val track: ReadaloudTrack,
        val streaming: SharedBundle.Streaming,
        val sidecarFile: File,
        val absToken: String,
    )

    suspend fun tryBuild(storytellerServerId: String, storytellerBookId: String): Session? = withContext(Dispatchers.IO) {
        // 1. Resolve the ABS audiobook linked to this readaloud. No audiobook → not streamable.
        val audiobook = audioIdentityResolver.resolveForStorytellerBook(storytellerServerId, storytellerBookId)
        if (audiobook.serverId == storytellerServerId && audiobook.bookId == storytellerBookId) {
            return@withContext null
        }

        val absServer = serverRepository.getById(audiobook.serverId) ?: return@withContext null
        val absToken = tokenStorage.getToken(audiobook.serverId) ?: return@withContext null

        // 2. Recording-identity gate: only stream when ABS audio is provably Storyteller's source.
        // Reuse the persisted verdict (ADR 0028) so a re-open doesn't re-fetch both fingerprints: a stored
        // VERIFIED is trusted as-is; a stored definitive negative (MISMATCH/NO_AUDIOBOOK) stays on the
        // bundle without a network round-trip. Only an as-yet-UNKNOWN link runs the fingerprint check —
        // and because it persists the verdict, a transient failure leaves it UNKNOWN so a later attempt
        // retries rather than being stuck (the source decision never trusts a non-VERIFIED result).
        val persisted = linkRepository.findByAbsItem(audiobook.serverId, audiobook.bookId)?.identityResult
        if (persisted != null && persisted != AudiobookIdentityResult.UNKNOWN) {
            if (persisted != AudiobookIdentityResult.VERIFIED) return@withContext null
        } else {
            val stServer = serverRepository.getById(storytellerServerId) ?: return@withContext null
            val stToken = tokenStorage.getToken(storytellerServerId) ?: return@withContext null
            val bookId = storytellerBookId.toLongOrNull() ?: return@withContext null
            val stFp = storytellerApi.getAudiobookFingerprint(stServer.url.value, bookId, stToken, stServer.insecureConnectionAllowed)
            val absFp = absApi.getAudiobookFingerprint(absServer.url.value, audiobook.bookId, absToken, absServer.insecureConnectionAllowed)
            val verdict = AudiobookIdentityResolver.resolve(stFp, absFp)
            linkRepository.updateIdentityResult(audiobook.serverId, audiobook.bookId, verdict)
            if (verdict != AudiobookIdentityResult.VERIFIED) return@withContext null
        }

        // 3. ABS audiobook tracks (ino + duration).
        val tracksResult = absApi.getAudiobookTracks(absServer.url.value, audiobook.bookId, absToken, absServer.insecureConnectionAllowed)
        val tracks = (tracksResult as? NetworkResult.Success)?.value?.takeIf { it.isNotEmpty() } ?: return@withContext null

        // 4. The sidecar (SMIL + text). Use ONLY the already-cached copy — the slow /synced fetch is done
        //    ahead of time by ReadaloudSidecarStore.prepare() when the book is opened (ADR 0028), so the
        //    Play path never blocks on it. Not prepared yet → null → the caller surfaces "Preparing…".
        val sidecarFile = sidecarStore.cachedFile(storytellerServerId, storytellerBookId)
            ?: return@withContext null

        // 5. Reconcile segments to tracks; null when timelines disagree → bundle.
        val setup = StreamingSetupBuilder().build(sidecarFile, tracks, absServer.url.value, audiobook.bookId, absToken)
        if (setup == null) {
            evictStaleOrPartialSidecar(storytellerServerId, storytellerBookId, sidecarFile, tracks.sumOf { it.durationSec })
            return@withContext null
        }

        val httpFactory = StreamingAudioCache.dataSourceFactory(context, absToken)
        Session(setup.track, SharedBundle.Streaming(httpFactory, setup.items.associateBy { it.audioSrc }), sidecarFile, absToken)
    }

    /**
     * When [StreamingSetupBuilder] returns null, inspect the sidecar to decide whether it's a stale
     * intermediate artifact (no clips, or covers only part of the book) and evict it so the next
     * prepare() fetches from the now-complete Storyteller bundle. Partial-sidecar eviction is guarded
     * to one attempt per session — if the re-fetched sidecar is also partial, alignment is still in
     * progress on the server and we stop trying until the next app launch.
     */
    private fun evictStaleOrPartialSidecar(serverId: String, bookId: String, sidecarFile: File, absTotalSec: Double) {
        val sidecarTrack = runCatching { MediaOverlayReader.readTrack(sidecarFile) }.getOrNull() ?: return
        val clips = sidecarTrack.clips
        val segments = clips.groupBy { it.audioSrc }
        val segTotal = if (segments.isNotEmpty()) segments.values.sumOf { group -> group.maxOf { it.clipEndSec } } else -1.0

        val isPartial = segTotal >= 0.0 && absTotalSec - segTotal > 10.0 * segments.size
        if (clips.isEmpty() || isPartial) {
            val bookKey = sidecarStore.key(serverId, bookId)
            if (evictedPartialSidecars.add(bookKey)) {
                sidecarStore.remove(serverId, bookId)
                sidecarStore.prepare(serverId, bookId)
            }
        }
    }
}

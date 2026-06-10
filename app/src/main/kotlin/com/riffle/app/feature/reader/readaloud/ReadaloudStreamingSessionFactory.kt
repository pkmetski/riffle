package com.riffle.app.feature.reader.readaloud

import android.content.Context
import com.riffle.core.data.AudiobookIdentityResolver
import com.riffle.core.data.StorytellerSidecarFetcher
import com.riffle.core.data.StreamingSetupBuilder
import com.riffle.core.domain.AudioIdentityResolver
import com.riffle.core.domain.AudiobookIdentityResult
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.AbsLibraryApi
import com.riffle.core.network.NetworkAudiobookTracksResult
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
    private val sidecarFetcher: StorytellerSidecarFetcher,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) {
    /** [sidecarFile] stands in for the bundle for the track, highlight quotes, and cross-EPUB index. */
    data class Session(val track: ReadaloudTrack, val streaming: SharedBundle.Streaming, val sidecarFile: File)

    suspend fun tryBuild(storytellerServerId: String, storytellerBookId: String): Session? = withContext(Dispatchers.IO) {
        // 1. Resolve the ABS audiobook linked to this readaloud. No audiobook → not streamable.
        val audiobook = audioIdentityResolver.resolveForStorytellerBook(storytellerServerId, storytellerBookId)
        if (audiobook.serverId == storytellerServerId && audiobook.bookId == storytellerBookId) return@withContext null

        val absServer = serverRepository.getById(audiobook.serverId) ?: return@withContext null
        val absToken = tokenStorage.getToken(audiobook.serverId) ?: return@withContext null
        val stServer = serverRepository.getById(storytellerServerId) ?: return@withContext null
        val stToken = tokenStorage.getToken(storytellerServerId) ?: return@withContext null
        val bookId = storytellerBookId.toLongOrNull() ?: return@withContext null

        // 2. Recording-identity gate: only stream when ABS audio is provably Storyteller's source.
        val stFp = storytellerApi.getAudiobookFingerprint(stServer.url.value, bookId, stToken, stServer.insecureConnectionAllowed)
        val absFp = absApi.getAudiobookFingerprint(absServer.url.value, audiobook.bookId, absToken, absServer.insecureConnectionAllowed)
        if (AudiobookIdentityResolver.resolve(stFp, absFp) != AudiobookIdentityResult.VERIFIED) return@withContext null

        // 3. ABS audiobook tracks (ino + duration).
        val tracks = when (val r = absApi.getAudiobookTracks(absServer.url.value, audiobook.bookId, absToken, absServer.insecureConnectionAllowed)) {
            is NetworkAudiobookTracksResult.Success -> r.tracks
            else -> return@withContext null
        }

        // 4. The sidecar (SMIL + text), range-extracted and cached on disk.
        val sidecarBytes = sidecarFetcher.fetch(stServer.url.value, storytellerBookId, stToken, stServer.insecureConnectionAllowed)
            ?: return@withContext null
        val sidecarFile = File(context.cacheDir, "ra-sidecar-$storytellerServerId-$storytellerBookId.epub")
            .apply { writeBytes(sidecarBytes) }

        // 5. Reconcile segments to tracks; null when timelines disagree → bundle.
        val setup = StreamingSetupBuilder().build(sidecarFile, tracks, absServer.url.value, audiobook.bookId)
            ?: return@withContext null

        val httpFactory = StreamingAudioCache.dataSourceFactory(context, absToken)
        Session(setup.track, SharedBundle.Streaming(httpFactory, setup.items.associateBy { it.audioSrc }), sidecarFile)
    }
}

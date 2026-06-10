package com.riffle.core.data

import android.content.Context
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches the Readaloud sidecar (SMIL + chapter text, ADR 0028) on disk, fetching it once via byte
 * ranges if absent. Shared by the streaming player and the cross-EPUB index builder so a streamed
 * book gets the same three-peer sync as a bundle book — the sidecar is the Storyteller EPUB minus
 * audio, so it feeds the index identically.
 *
 * Kept apart from the bundle/EPUB stores: a sidecar must never be mistaken for a downloaded bundle
 * (it has no audio), which would break bundle playback.
 */
@Singleton
class ReadaloudSidecarStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fetcher: StorytellerSidecarFetcher,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) {
    private fun dir(): File = File(context.cacheDir, "readaloud-sidecars").apply { mkdirs() }

    /** The cached sidecar file for a readaloud, fetching+caching it on first use, or null if unavailable. */
    suspend fun get(storytellerServerId: String, storytellerBookId: String): File? = withContext(Dispatchers.IO) {
        val file = File(dir(), "$storytellerServerId-$storytellerBookId.epub")
        if (file.exists() && file.length() > 0) return@withContext file
        val server = serverRepository.getById(storytellerServerId) ?: return@withContext null
        val token = tokenStorage.getToken(storytellerServerId) ?: return@withContext null
        val bytes = fetcher.fetch(server.url.value, storytellerBookId, token, server.insecureConnectionAllowed)
            ?: return@withContext null
        file.apply { writeBytes(bytes) }
    }
}

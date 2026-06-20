package com.riffle.core.data

import android.content.Context
import com.riffle.core.domain.ReadaloudSidecarCache
import com.riffle.core.domain.ServerRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches the Readaloud sidecar (SMIL + chapter text, ADR 0028) on disk and prepares it **ahead of
 * playback**: callers [prepare] it the moment a matched book is opened (details or reader), so that
 * by the time the user taps Play the streaming session can be built instantly from the cached file
 * — no `/synced` fetch on the Play path (which would block for the server's bundle-generation time).
 *
 * The fetch is a single streaming GET that keeps only the ~1 MB non-audio prefix (see
 * [StorytellerSidecarFetcher]); the audio is streamed from ABS at play time, never bundled here.
 *
 * Lives in the **cache** dir (not Downloads): it's a transparent, OS-evictable artifact, distinct from
 * an explicit full-bundle download. A downloaded bundle supersedes it (the bundle already has the
 * sidecar content + audio), so callers prefer the bundle when present.
 */
/**
 * Narrow seam for "start preparing this book's sidecar" — lets callers (e.g. the details screen) kick
 * off a prefetch without depending on the whole [ReadaloudSidecarStore] (and keeps them unit-testable).
 */
fun interface ReadaloudSidecarPrefetcher {
    fun prepare(storytellerServerId: String, storytellerBookId: String)
}

@Singleton
class ReadaloudSidecarStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fetcher: StorytellerSidecarFetcher,
    private val serverRepository: ServerRepository,
    private val tokenStorage: TokenStorage,
) : ReadaloudSidecarPrefetcher, ReadaloudSidecarCache {
    enum class State { Preparing, Ready, Failed }

    // App-scoped: a prepare started on the details screen must survive into the reader (and vice versa),
    // so it can't hang off a ViewModel scope. SupervisorJob so one book's failure doesn't cancel others.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val inFlight = HashMap<String, Deferred<File?>>()
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())

    /** Per-book prepare states, keyed by [key]. A book is also implicitly [State.Ready] when [cachedFile] exists. */
    val states: StateFlow<Map<String, State>> = _states

    fun key(storytellerServerId: String, storytellerBookId: String) = "$storytellerServerId-$storytellerBookId"

    private fun dir(): File = File(context.cacheDir, "readaloud-sidecars").apply { mkdirs() }
    private fun fileFor(serverId: String, bookId: String) = File(dir(), "${key(serverId, bookId)}.epub")

    /** The cached sidecar if it's already on disk — never triggers a fetch. */
    override fun cachedFile(storytellerServerId: String, storytellerBookId: String): File? =
        fileFor(storytellerServerId, storytellerBookId).takeIf { it.exists() && it.length() > 0 }

    /** State for the bar/UX: Ready when cached, else the in-flight/last prepare outcome. */
    fun stateOf(storytellerServerId: String, storytellerBookId: String): State? =
        if (cachedFile(storytellerServerId, storytellerBookId) != null) State.Ready
        else _states.value[key(storytellerServerId, storytellerBookId)]

    /** Idempotently start (or join) a background prepare. No-op if already cached or in flight. */
    override fun prepare(storytellerServerId: String, storytellerBookId: String) {
        if (cachedFile(storytellerServerId, storytellerBookId) != null) return
        scope.launch { ensurePrepared(storytellerServerId, storytellerBookId) }
    }

    /** Suspends until the sidecar is ready, joining any in-flight prepare; null on failure/timeout. */
    suspend fun get(storytellerServerId: String, storytellerBookId: String): File? =
        ensurePrepared(storytellerServerId, storytellerBookId)

    private suspend fun ensurePrepared(storytellerServerId: String, storytellerBookId: String): File? {
        cachedFile(storytellerServerId, storytellerBookId)?.let { return it }
        val deferred = mutex.withLock {
            cachedFile(storytellerServerId, storytellerBookId)?.let { return it }
            inFlight[key(storytellerServerId, storytellerBookId)]
                ?: scope.async { doPrepare(storytellerServerId, storytellerBookId) }
                    .also { inFlight[key(storytellerServerId, storytellerBookId)] = it }
        }
        return deferred.await()
    }

    private suspend fun doPrepare(storytellerServerId: String, storytellerBookId: String): File? {
        setState(storytellerServerId, storytellerBookId, State.Preparing)
        val file = withTimeoutOrNull(PREPARE_TIMEOUT_MS) {
            val server = serverRepository.getById(storytellerServerId) ?: return@withTimeoutOrNull null
            val token = tokenStorage.getToken(storytellerServerId) ?: return@withTimeoutOrNull null
            val bytes = fetcher.fetch(server.url.value, storytellerBookId, token, server.insecureConnectionAllowed)
            bytes?.let { fileFor(storytellerServerId, storytellerBookId).apply { writeBytes(it) } }
        }
        mutex.withLock { inFlight.remove(key(storytellerServerId, storytellerBookId)) }
        setState(storytellerServerId, storytellerBookId, if (file != null) State.Ready else State.Failed)
        return file
    }

    private fun setState(serverId: String, bookId: String, state: State) {
        _states.value = _states.value + (key(serverId, bookId) to state)
    }

    /** A prepared sidecar on disk — surfaced in the Downloads screen so the user can see/clear it. */
    data class CachedSidecar(val storytellerServerId: String, val storytellerBookId: String, val sizeBytes: Long)

    /** All cached sidecars. The filename is `<storytellerServerId>-<storytellerBookId>.epub`; the book id
     *  is numeric (no hyphens), so the last hyphen splits it from the (UUID) server id. */
    fun listCached(): List<CachedSidecar> =
        dir().listFiles().orEmpty().filter { it.isFile && it.extension == "epub" && it.length() > 0 }.mapNotNull { f ->
            val name = f.nameWithoutExtension
            val serverId = name.substringBeforeLast('-', "")
            val bookId = name.substringAfterLast('-', "")
            if (serverId.isEmpty() || bookId.isEmpty()) null else CachedSidecar(serverId, bookId, f.length())
        }

    /** Delete one prepared sidecar (it'll be re-prepared on the next open if still needed). */
    fun remove(storytellerServerId: String, storytellerBookId: String) {
        fileFor(storytellerServerId, storytellerBookId).delete()
        _states.value = _states.value - key(storytellerServerId, storytellerBookId)
    }

    /** Delete every prepared sidecar. */
    fun clearAll() {
        dir().listFiles().orEmpty().forEach { it.delete() }
        _states.value = emptyMap()
    }

    private companion object {
        // Storyteller can take a minute+ to generate /synced; bound the background prepare so a dead
        // server fails it rather than leaving it pending forever.
        const val PREPARE_TIMEOUT_MS = 240_000L
    }
}

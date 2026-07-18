package com.riffle.core.data

import android.content.Context
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.ReadaloudSidecarCache
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    fun prepare(storytellerSourceId: String, storytellerBookId: String)
}

@Singleton
class ReadaloudSidecarStore private constructor(
    private val cacheRootDir: () -> File,
    private val fetcher: StorytellerSidecarFetcher,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    // App-scoped: a prepare started on the details screen must survive into the reader (and vice versa),
    // so it can't hang off a ViewModel scope. SupervisorJob so one book's failure doesn't cancel others.
    private val scope: CoroutineScope,
) : ReadaloudSidecarPrefetcher, ReadaloudSidecarCache {

    @Inject constructor(
        @ApplicationContext context: Context,
        fetcher: StorytellerSidecarFetcher,
        sourceRepository: SourceRepository,
        tokenStorage: TokenStorage,
        applicationScope: ApplicationScope,
    ) : this(
        cacheRootDir = { context.cacheDir },
        fetcher = fetcher,
        sourceRepository = sourceRepository,
        tokenStorage = tokenStorage,
        scope = applicationScope.coroutineScope,
    )

    internal constructor(
        cacheRootDir: File,
        fetcher: StorytellerSidecarFetcher,
        sourceRepository: SourceRepository,
        tokenStorage: TokenStorage,
        scope: CoroutineScope,
    ) : this(
        cacheRootDir = { cacheRootDir },
        fetcher = fetcher,
        sourceRepository = sourceRepository,
        tokenStorage = tokenStorage,
        scope = scope,
    )

    enum class State { Preparing, Ready, Failed }

    init {
        // Purge cached sidecars that are unplayable: (1) no SMIL at all (fetched before hasSMIL
        // validation, e.g. Storyteller at SPLIT_TRACKS stage), or (2) SMIL files present but with
        // no <par> clips — fetched during an intermediate alignment stage where SMIL structure was
        // written but sentence-level alignment had not yet run. Both cases make stateOf() return
        // Ready and prepare() a no-op, so streaming always fails. Deleting forces a fresh fetch.
        scope.launch {
            dir().listFiles().orEmpty()
                .filter { it.isFile && it.extension == SIDECAR_EXTENSION && it.length() > 0 }
                .forEach { file ->
                    val hasClips = runCatching {
                        MediaOverlayReader.readTrack(file).clips.isNotEmpty()
                    }.getOrDefault(true) // keep on read error — not ours to diagnose
                    if (!hasClips) {
                        file.delete()
                    }
                }
            enforceLruBudget()
        }
    }

    private val mutex = Mutex()
    private val inFlight = HashMap<String, Deferred<File?>>()
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())

    /** Per-book prepare states, keyed by [key]. A book is also implicitly [State.Ready] when [cachedFile] exists. */
    val states: StateFlow<Map<String, State>> = _states

    fun key(storytellerSourceId: String, storytellerBookId: String) = "$storytellerSourceId$KEY_SEPARATOR$storytellerBookId"

    private fun dir(): File = File(cacheRootDir(), SIDECAR_DIR_NAME).apply { mkdirs() }
    private fun fileFor(sourceId: String, bookId: String) = File(dir(), "${key(sourceId, bookId)}.$SIDECAR_EXTENSION")

    /** The cached sidecar if it's already on disk — never triggers a fetch. */
    override fun cachedFile(storytellerSourceId: String, storytellerBookId: String): File? =
        fileFor(storytellerSourceId, storytellerBookId).takeIf { it.exists() && it.length() > 0 }

    /** State for the bar/UX: Ready when cached, else the in-flight/last prepare outcome. */
    fun stateOf(storytellerSourceId: String, storytellerBookId: String): State? {
        val cached = cachedFile(storytellerSourceId, storytellerBookId)
        return if (cached != null) State.Ready else _states.value[key(storytellerSourceId, storytellerBookId)]
    }

    /** Idempotently start (or join) a background prepare. No-op if already cached, already failed, or in flight. */
    override fun prepare(storytellerSourceId: String, storytellerBookId: String) {
        if (cachedFile(storytellerSourceId, storytellerBookId) != null) return
        // Don't retry a book that already exhausted all attempts this session. The in-memory state
        // resets on process restart, giving one fresh retry cycle per session. Without this guard,
        // every navigation back to the book re-launches the full retry loop (4 × ~30 MB downloads).
        if (_states.value[key(storytellerSourceId, storytellerBookId)] == State.Failed) return
        // Set Preparing synchronously so that any stateOf() check after this call (but before the
        // background coroutine runs) already sees Preparing — avoiding a window where the UI shows
        // "Couldn't stream" instead of "Preparing…" when eviction+re-prepare happen on the Play path.
        setState(storytellerSourceId, storytellerBookId, State.Preparing)
        scope.launch { ensurePrepared(storytellerSourceId, storytellerBookId) }
    }

    /** Suspends until the sidecar is ready, joining any in-flight prepare; null on failure/timeout. */
    suspend fun get(storytellerSourceId: String, storytellerBookId: String): File? =
        ensurePrepared(storytellerSourceId, storytellerBookId)

    private suspend fun ensurePrepared(storytellerSourceId: String, storytellerBookId: String): File? {
        cachedFile(storytellerSourceId, storytellerBookId)?.let { return it }
        val deferred = mutex.withLock {
            cachedFile(storytellerSourceId, storytellerBookId)?.let { return it }
            inFlight[key(storytellerSourceId, storytellerBookId)]
                ?: scope.async { doPrepare(storytellerSourceId, storytellerBookId) }
                    .also { inFlight[key(storytellerSourceId, storytellerBookId)] = it }
        }
        return deferred.await()
    }

    private suspend fun doPrepare(storytellerSourceId: String, storytellerBookId: String): File? {
        setState(storytellerSourceId, storytellerBookId, State.Preparing)
        val source = sourceRepository.getById(storytellerSourceId)
        val token = if (source != null) tokenStorage.getToken(storytellerSourceId) else null
        var file: File? = null
        if (source != null && token != null) {
            for (attempt in 0..MAX_RETRIES) {
                if (attempt > 0) delay(RETRY_BACKOFF_MS[attempt - 1])
                when (val result = fetcher.fetch(source.url.value, storytellerBookId, token, source.insecureConnectionAllowed)) {
                    is StorytellerSidecarFetcher.FetchResult.Success -> {
                        file = fileFor(storytellerSourceId, storytellerBookId).apply { writeBytes(result.bytes) }
                        enforceLruBudget()
                        break
                    }
                    // Book definitively has no SMIL — no point retrying until Storyteller aligns it.
                    StorytellerSidecarFetcher.FetchResult.NotAligned -> break
                    // Transient transport failure — retry up to MAX_RETRIES times.
                    StorytellerSidecarFetcher.FetchResult.NetworkError -> { /* continue loop */ }
                }
            }
        }
        mutex.withLock { inFlight.remove(key(storytellerSourceId, storytellerBookId)) }
        setState(storytellerSourceId, storytellerBookId, if (file != null) State.Ready else State.Failed)
        return file
    }

    private fun setState(sourceId: String, bookId: String, state: State) {
        _states.value = _states.value + (key(sourceId, bookId) to state)
    }

    /** A prepared sidecar on disk — surfaced in the Downloads screen so the user can see/clear it. */
    data class CachedSidecar(val storytellerSourceId: String, val storytellerBookId: String, val sizeBytes: Long)

    /** All cached sidecars. The filename is `<storytellerSourceId>-<storytellerBookId>.epub`; the book id
     *  is numeric (no hyphens), so the last hyphen splits it from the (UUID) server id. */
    fun listCached(): List<CachedSidecar> =
        dir().listFiles().orEmpty().filter { it.isFile && it.extension == SIDECAR_EXTENSION && it.length() > 0 }.mapNotNull { f ->
            val name = f.nameWithoutExtension
            val sourceId = name.substringBeforeLast('-', "")
            val bookId = name.substringAfterLast('-', "")
            if (sourceId.isEmpty() || bookId.isEmpty()) null else CachedSidecar(sourceId, bookId, f.length())
        }

    /** Delete one prepared sidecar (it'll be re-prepared on the next open if still needed). */
    fun remove(storytellerSourceId: String, storytellerBookId: String) {
        fileFor(storytellerSourceId, storytellerBookId).delete()
        _states.value = _states.value - key(storytellerSourceId, storytellerBookId)
    }

    /** Delete every prepared sidecar. */
    fun clearAll() {
        dir().listFiles().orEmpty().forEach { it.delete() }
        _states.value = emptyMap()
    }

    /**
     * Deletes every cached sidecar belonging to [storytellerSourceId]. Called from the source-removal
     * path so a re-added source doesn't inherit stale sidecars keyed to its previous id, and so the
     * cache dir doesn't retain files whose owning source no longer exists.
     */
    override fun purgeSource(storytellerSourceId: String) {
        val prefix = "$storytellerSourceId$KEY_SEPARATOR"
        dir().listFiles().orEmpty()
            .filter { it.isFile && it.extension == SIDECAR_EXTENSION && it.name.startsWith(prefix) }
            .forEach { it.delete() }
        _states.value = _states.value.filterKeys { !it.startsWith(prefix) }
    }

    /**
     * Evicts oldest sidecars until the on-disk footprint is within [capBytes]. Oldest is ordered by
     * [File.lastModified]; on a successful write the just-written file has the newest mtime, so it
     * is only evicted if a single sidecar exceeds the cap (which shouldn't happen since the
     * streaming fetcher keeps only the ~1 MB non-audio prefix). Returns the set of evicted keys
     * (the base filename without extension) for test assertions and internal state cleanup.
     */
    internal fun enforceLruBudget(capBytes: Long = MAX_CACHE_BYTES): Set<String> {
        val files = dir().listFiles().orEmpty()
            .filter { it.isFile && it.extension == SIDECAR_EXTENSION && it.length() > 0 }
        var total = files.sumOf { it.length() }
        if (total <= capBytes) return emptySet()
        val evicted = mutableSetOf<String>()
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= capBytes) return@forEach
            val size = file.length()
            if (file.delete()) {
                total -= size
                evicted += file.nameWithoutExtension
            }
        }
        if (evicted.isNotEmpty()) {
            _states.value = _states.value.filterKeys { it !in evicted }
        }
        return evicted
    }

    private companion object {
        // 3 retries (4 total attempts). Each attempt is bounded by sidecarStreamClient's callTimeout(240s).
        // Backoff covers transient server load; the dominant cost per attempt is the 240s network timeout.
        const val MAX_RETRIES = 3
        val RETRY_BACKOFF_MS = longArrayOf(30_000L, 60_000L, 120_000L)
        // 200 MB cap. A sidecar is the ~1 MB non-audio prefix, so this comfortably holds 100+ books
        // before eviction kicks in — enough that no realistic session hits the ceiling, but bounded
        // enough that a rarely-cleaned-cache install doesn't grow indefinitely.
        const val MAX_CACHE_BYTES: Long = 200L * 1024L * 1024L
        // Filename shape is `<sourceId><KEY_SEPARATOR><bookId>.<SIDECAR_EXTENSION>`. The extension
        // is .epub because the sidecar is an EPUB container carrying only the SMIL + text prefix
        // (no audio). Both are referenced from the cache-dir name and every listFiles filter, so
        // they live here rather than as inline literals — a rename can then land in one place.
        const val SIDECAR_EXTENSION: String = "epub"
        const val SIDECAR_DIR_NAME: String = "readaloud-sidecars"
        const val KEY_SEPARATOR: String = "-"
    }
}

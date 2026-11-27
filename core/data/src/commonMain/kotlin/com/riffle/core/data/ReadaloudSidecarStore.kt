package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.ReadaloudSidecarDownloads
import com.riffle.core.domain.ReadaloudSidecarPrefetcher
import com.riffle.core.domain.ReadaloudSidecarPurger
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath

// Platform-neutral re-fetchable cache for the Readaloud sidecar (SMIL + chapter text, ADR 0040),
// prepared ahead of playback so Play builds a streaming session without a /synced fetch.
class ReadaloudSidecarStore(
    private val fetcher: StorytellerSidecarFetcher,
    private val clipInspector: SidecarClipInspector,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val fileStore: FileStore,
    private val applicationScope: ApplicationScope,
    private val dispatchers: DispatcherProvider,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : ReadaloudSidecarPrefetcher, ReadaloudSidecarDownloads, ReadaloudSidecarPurger {

    enum class State { Preparing, Ready, Failed }

    // App-scoped: a prepare started on the details screen must survive into the reader (and vice
    // versa), so it can't hang off a ViewModel scope.
    private val scope: CoroutineScope = applicationScope.coroutineScope

    private val mutex = Mutex()
    private val inFlight = HashMap<String, Deferred<String?>>()
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())

    /** Per-book prepare states, keyed by [key]. A book is also implicitly [State.Ready] when [cachedPath] exists. */
    val states: StateFlow<Map<String, State>> = _states

    init {
        // A clipless sidecar (SMIL without <par>, fetched mid-alignment) makes stateOf() Ready and
        // prepare() a no-op, so streaming always fails — delete it to force a fresh fetch.
        scope.launch {
            withContext(dispatchers.io) {
                allSidecarFiles().forEach { file ->
                    val bytes = runCatching { fileSystem.read(file) { readByteArray() } }.getOrNull()
                    if (bytes != null && bytes.isNotEmpty() && !clipInspector.hasClips(bytes)) {
                        runCatching { fileSystem.delete(file) }
                    }
                }
            }
            enforceLruBudget()
        }
    }

    fun key(storytellerSourceId: String, storytellerBookId: String) = "$storytellerSourceId$KEY_SEPARATOR$storytellerBookId"

    private fun pathFor(sourceId: String, bookId: String): String =
        fileStore.resolve(NS_READALOUD_SIDECARS, "$sourceId/$bookId$SIDECAR_EXTENSION")

    /** Absolute path of the cached sidecar if it's already on disk — never triggers a fetch. */
    fun cachedPath(storytellerSourceId: String, storytellerBookId: String): String? {
        val path = pathFor(storytellerSourceId, storytellerBookId)
        return if (sizeOf(path.toPath()) > 0L) path else null
    }

    /** Raw bytes of the cached sidecar, or null when not cached or unreadable. */
    fun readSidecarBytes(storytellerSourceId: String, storytellerBookId: String): ByteArray? {
        val path = pathFor(storytellerSourceId, storytellerBookId).toPath()
        if (!fileSystem.exists(path)) return null
        return runCatching { fileSystem.read(path) { readByteArray() } }.getOrNull()
    }

    /** State for the bar/UX: Ready when cached, else the in-flight/last prepare outcome. */
    fun stateOf(storytellerSourceId: String, storytellerBookId: String): State? =
        if (cachedPath(storytellerSourceId, storytellerBookId) != null) {
            State.Ready
        } else {
            _states.value[key(storytellerSourceId, storytellerBookId)]
        }

    /** Idempotently start (or join) a background prepare. No-op if already cached, already failed, or in flight. */
    override fun prepare(storytellerSourceId: String, storytellerBookId: String) {
        if (cachedPath(storytellerSourceId, storytellerBookId) != null) return
        // Failed is terminal for the session (state resets on process restart) — avoids re-launching the retry loop on every revisit.
        if (_states.value[key(storytellerSourceId, storytellerBookId)] == State.Failed) return
        // Set Preparing synchronously so a stateOf() before the coroutine runs doesn't flash "Couldn't stream".
        setState(storytellerSourceId, storytellerBookId, State.Preparing)
        scope.launch { ensurePrepared(storytellerSourceId, storytellerBookId) }
    }

    /** Suspends until the sidecar is ready, joining any in-flight prepare; null on failure. */
    suspend fun get(storytellerSourceId: String, storytellerBookId: String): String? =
        ensurePrepared(storytellerSourceId, storytellerBookId)

    private suspend fun ensurePrepared(storytellerSourceId: String, storytellerBookId: String): String? {
        cachedPath(storytellerSourceId, storytellerBookId)?.let { return it }
        val deferred = mutex.withLock {
            cachedPath(storytellerSourceId, storytellerBookId)?.let { return it }
            inFlight[key(storytellerSourceId, storytellerBookId)]
                ?: scope.async { doPrepare(storytellerSourceId, storytellerBookId) }
                    .also { inFlight[key(storytellerSourceId, storytellerBookId)] = it }
        }
        return deferred.await()
    }

    private suspend fun doPrepare(storytellerSourceId: String, storytellerBookId: String): String? {
        setState(storytellerSourceId, storytellerBookId, State.Preparing)
        val source = sourceRepository.getById(storytellerSourceId)
        val token = if (source != null) tokenStorage.getToken(storytellerSourceId) else null
        var path: String? = null
        if (source != null && token != null) {
            for (attempt in 0..MAX_RETRIES) {
                if (attempt > 0) delay(RETRY_BACKOFF_MS[attempt - 1])
                when (val result = fetcher.fetch(source.url.value, storytellerBookId, token, source.insecureConnectionAllowed)) {
                    is StorytellerSidecarFetcher.FetchResult.Success -> {
                        path = writeSidecar(storytellerSourceId, storytellerBookId, result.bytes)
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
        setState(storytellerSourceId, storytellerBookId, if (path != null) State.Ready else State.Failed)
        return path
    }

    private suspend fun writeSidecar(sourceId: String, bookId: String, bytes: ByteArray): String = withContext(dispatchers.io) {
        val path = pathFor(sourceId, bookId).toPath()
        path.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(path) { write(bytes) }
        path.toString()
    }

    private fun setState(sourceId: String, bookId: String, state: State) {
        _states.value = _states.value + (key(sourceId, bookId) to state)
    }

    // Layout is <NS_READALOUD_SIDECARS>/<sourceId>/<bookId><SIDECAR_EXTENSION>: source id is the parent dir, book id the filename.
    override fun listCached(): List<ReadaloudSidecarDownloads.CachedSidecar> =
        allSidecarFiles().mapNotNull { file ->
            val size = sizeOf(file)
            if (size <= 0L) return@mapNotNull null
            val sourceId = file.parent?.name ?: return@mapNotNull null
            val bookId = file.name.removeSuffix(SIDECAR_EXTENSION)
            if (sourceId.isEmpty() || bookId.isEmpty()) {
                null
            } else {
                ReadaloudSidecarDownloads.CachedSidecar(sourceId, bookId, size)
            }
        }

    /** Delete one prepared sidecar (it'll be re-prepared on the next open if still needed). */
    override fun remove(storytellerSourceId: String, storytellerBookId: String) {
        runCatching { fileSystem.delete(pathFor(storytellerSourceId, storytellerBookId).toPath()) }
        _states.value = _states.value - key(storytellerSourceId, storytellerBookId)
    }

    /** Delete every prepared sidecar. */
    override fun clearAll() {
        allSidecarFiles().forEach { runCatching { fileSystem.delete(it) } }
        _states.value = emptyMap()
    }

    // Source-removal path: drop all of a source's sidecars so a re-added source can't inherit stale ones.
    override fun purgeSource(storytellerSourceId: String) {
        runCatching {
            fileSystem.deleteRecursively(fileStore.resolve(NS_READALOUD_SIDECARS, storytellerSourceId).toPath())
        }
        _states.value = _states.value.filterKeys { it != key(storytellerSourceId, "") && !it.startsWith("$storytellerSourceId$KEY_SEPARATOR") }
    }

    // Evicts oldest-by-mtime sidecars until the footprint is within [capBytes]; returns evicted [key]s.
    internal fun enforceLruBudget(capBytes: Long = MAX_CACHE_BYTES): Set<String> {
        val files = allSidecarFiles().mapNotNull { path ->
            val md = fileSystem.metadataOrNull(path) ?: return@mapNotNull null
            val size = md.size ?: return@mapNotNull null
            if (size <= 0L) null else FileEntry(path, size, md.lastModifiedAtMillis ?: 0L)
        }
        var total = files.sumOf { it.size }
        if (total <= capBytes) return emptySet()
        val evicted = mutableSetOf<String>()
        files.sortedBy { it.lastModifiedMillis }.forEach { entry ->
            if (total <= capBytes) return@forEach
            val deleted = runCatching { fileSystem.delete(entry.path) }.isSuccess
            if (deleted) {
                total -= entry.size
                val sourceId = entry.path.parent?.name
                val bookId = entry.path.name.removeSuffix(SIDECAR_EXTENSION)
                if (sourceId != null) evicted += key(sourceId, bookId)
            }
        }
        if (evicted.isNotEmpty()) {
            _states.value = _states.value.filterKeys { it !in evicted }
        }
        return evicted
    }

    private fun sizeOf(path: okio.Path): Long =
        if (fileSystem.exists(path)) fileSystem.metadataOrNull(path)?.size ?: 0L else 0L

    private fun allSidecarFiles(): List<okio.Path> {
        val root = fileStore.resolve(NS_READALOUD_SIDECARS).toPath()
        if (!fileSystem.exists(root)) return emptyList()
        return fileSystem.list(root).flatMap { sourceDir ->
            if (fileSystem.metadataOrNull(sourceDir)?.isDirectory == true) {
                fileSystem.list(sourceDir).filter { it.name.endsWith(SIDECAR_EXTENSION) }
            } else {
                emptyList()
            }
        }
    }

    private data class FileEntry(val path: okio.Path, val size: Long, val lastModifiedMillis: Long)

    private companion object {
        // 3 retries (4 total attempts). Each attempt is bounded by the fetcher's own call timeout.
        const val MAX_RETRIES = 3
        val RETRY_BACKOFF_MS = longArrayOf(30_000L, 60_000L, 120_000L)

        // 200 MB cap holds 100+ of the ~1 MB sidecar prefixes before eviction.
        const val MAX_CACHE_BYTES: Long = 200L * 1024L * 1024L

        // .epub: the sidecar is an EPUB container with only the SMIL + text prefix, no audio.
        const val SIDECAR_EXTENSION: String = ".epub"
        const val KEY_SEPARATOR: String = "-"
    }
}

package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.ReadaloudSidecarDownloads
import com.riffle.core.domain.ReadaloudSidecarPrefetcher
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.network.withHttpChannelStream
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSFileManager

const val NS_READALOUD_SIDECARS = "readaloud-sidecars"

/**
 * iOS counterpart to `ReadaloudSidecarStore` (ADR 0040): caches the Readaloud sidecar — the
 * `/synced` bundle minus its audio — and prepares it **ahead of playback**, so tapping Play does
 * not have to wait on Storyteller's bundle generation.
 *
 * Lives in a cache namespace rather than downloads: it is a transparent, re-fetchable artifact,
 * distinct from an explicit full-bundle download. A downloaded bundle supersedes it (the bundle
 * already contains everything the sidecar does), so callers prefer the bundle when present.
 *
 * [prepare] is fire-and-forget on the survivable application scope — a prepare started on the
 * details screen must survive into the reader — and de-duplicates concurrent requests for the same
 * book, matching Android's per-book state machine.
 */
@OptIn(ExperimentalForeignApi::class)
class IosReadaloudSidecarStore(
    private val fileStore: FileStore,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val httpClient: HttpClient,
    private val applicationScope: ApplicationScope,
) : ReadaloudSidecarPrefetcher, ReadaloudSidecarDownloads {

    enum class State { Preparing, Ready, Failed }

    private val states = MutableStateFlow<Map<String, State>>(emptyMap())
    private val mutex = Mutex()

    /** Observable per-book prepare state, keyed as "<storytellerSourceId>/<storytellerBookId>". */
    val prepareStates: StateFlow<Map<String, State>> = states.asStateFlow()

    override fun prepare(storytellerSourceId: String, storytellerBookId: String) {
        val key = key(storytellerSourceId, storytellerBookId)
        applicationScope.launchSurvivable {
            mutex.withLock {
                if (states.value[key] == State.Preparing) return@launchSurvivable
                if (cachedSidecarPath(storytellerSourceId, storytellerBookId) != null) {
                    states.value = states.value + (key to State.Ready)
                    return@launchSurvivable
                }
                states.value = states.value + (key to State.Preparing)
            }
            val ok = fetchAndStore(storytellerSourceId, storytellerBookId)
            states.value = states.value + (key to if (ok) State.Ready else State.Failed)
        }
    }

    /** Absolute path of the cached sidecar, or null when it has not been prepared. */
    fun cachedSidecarPath(storytellerSourceId: String, storytellerBookId: String): String? {
        val path = sidecarPath(storytellerSourceId, storytellerBookId)
        return if (IosAudiobookFiles.exists(path)) path else null
    }

    override fun listCached(): List<ReadaloudSidecarDownloads.CachedSidecar> {
        val root = fileStore.resolve(NS_READALOUD_SIDECARS)
        val manager = NSFileManager.defaultManager

        @Suppress("UNCHECKED_CAST")
        val sourceDirs = manager.contentsOfDirectoryAtPath(root, error = null) as? List<String> ?: return emptyList()
        return sourceDirs.flatMap { sourceId ->
            @Suppress("UNCHECKED_CAST")
            val files = manager.contentsOfDirectoryAtPath("$root/$sourceId", error = null) as? List<String>
                ?: return@flatMap emptyList()
            files.filter { it.endsWith(SIDECAR_EXTENSION) }.map { fileName ->
                ReadaloudSidecarDownloads.CachedSidecar(
                    storytellerSourceId = sourceId,
                    storytellerBookId = fileName.removeSuffix(SIDECAR_EXTENSION),
                    sizeBytes = IosAudiobookFiles.directorySize("$root/$sourceId/$fileName")
                        .takeIf { it > 0L }
                        ?: (IosAudiobookFiles.readBytes("$root/$sourceId/$fileName")?.size?.toLong() ?: 0L),
                )
            }
        }
    }

    override fun clearAll() {
        IosAudiobookFiles.deleteRecursively(fileStore.resolve(NS_READALOUD_SIDECARS))
        states.value = emptyMap()
    }

    override fun remove(storytellerSourceId: String, storytellerBookId: String) {
        IosAudiobookFiles.deleteRecursively(sidecarPath(storytellerSourceId, storytellerBookId))
        states.value = states.value - key(storytellerSourceId, storytellerBookId)
    }

    /** Deletes every cached sidecar owned by [storytellerSourceId] (source-removal path). */
    fun purgeSource(storytellerSourceId: String) {
        IosAudiobookFiles.deleteRecursively("${fileStore.resolve(NS_READALOUD_SIDECARS)}/$storytellerSourceId")
        states.value = states.value.filterKeys { !it.startsWith("$storytellerSourceId/") }
    }

    private suspend fun fetchAndStore(storytellerSourceId: String, storytellerBookId: String): Boolean {
        val source = sourceRepository.getById(storytellerSourceId) ?: return false
        val token = tokenStorage.getToken(source.id) ?: return false
        val url = "${source.url.value.trimEnd('/')}/api/books/$storytellerBookId/synced"

        val bundle = runCatching {
            httpClient.withHttpChannelStream(
                url = url,
                headers = mapOf(HttpHeaders.Authorization to "Bearer $token"),
            ) { stream ->
                val chunks = mutableListOf<ByteArray>()
                var total = 0
                val buffer = ByteArray(64 * 1024)
                while (!stream.channel.isClosedForRead) {
                    val read = stream.channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    chunks += buffer.copyOfRange(0, read)
                    total += read
                }
                val body = ByteArray(total)
                var offset = 0
                for (chunk in chunks) {
                    chunk.copyInto(body, offset)
                    offset += chunk.size
                }
                body
            }
        }.getOrNull() ?: return false
        if (bundle.isEmpty()) return false

        // Null means the bundle carries no SMIL — Storyteller has not aligned this book yet, so
        // there is nothing worth caching and a retry only helps after alignment.
        val sidecar = IosReadaloudSidecarReader.read(bundle) ?: return false
        return IosAudiobookFiles.writeBytes(sidecarPath(storytellerSourceId, storytellerBookId), sidecar)
    }

    private fun sidecarPath(storytellerSourceId: String, storytellerBookId: String) =
        fileStore.resolve(NS_READALOUD_SIDECARS, "$storytellerSourceId/$storytellerBookId$SIDECAR_EXTENSION")

    private fun key(storytellerSourceId: String, storytellerBookId: String) =
        "$storytellerSourceId/$storytellerBookId"

    private companion object {
        const val SIDECAR_EXTENSION = ".epub"
    }
}

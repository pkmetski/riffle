package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.logging.NoopLogger
import com.riffle.core.sources.webdav.WebDavPlaylist
import com.riffle.core.sources.webdav.WebDavPlaylistSyncer
import com.riffle.core.sources.webdav.WebDavProgressRemoteFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-shot sweep that syncs the To Read list for every web source's libraries.
 * Called at app-start and on reconnect via [ForegroundSyncDriver] (iOS) and
 * [kickSweepsOnReconnect] (Android).
 *
 * Merge strategy: last-write-wins on [WebDavPlaylist.lastUpdate] epoch-ms. If remote.lastUpdate >
 * local.lastUpdateMs → adopt remote items. If local > remote → push local. Equal → no-op.
 *
 * Silently skips when WebDAV is not configured ([AnnotationSyncConfigStore.observe] returns null).
 *
 * A fresh [WebDavPlaylistSyncer] is constructed on each [run] call (after the config guard) so
 * that credentials updated after app-start are always picked up — no stale singleton.
 */
class PlaylistSweep(
    private val sourceRepository: SourceRepository,
    private val libraryDao: LibraryDao,
    private val localToReadStore: LocalToReadStore,
    private val httpClient: HttpClient,
    private val configStore: AnnotationSyncConfigStore,
    /** Overridable in tests to inject a [WebDavPlaylistSyncer] fake without going through HTTP. */
    internal val syncerFactory: (AnnotationSyncConfig, HttpClient) -> WebDavPlaylistSyncer =
        { config, client -> WebDavPlaylistSyncer(config, client) },
    private val logger: Logger = NoopLogger,
) {
    /**
     * In-memory cache of the last-known remote timestamp per library, keyed by libraryId.
     * Populated after every successful sync (push or pull) so subsequent sweeps can skip the GET
     * when local and remote are already in agreement. Survives across [run] calls (singleton), but
     * resets on process restart — causing at most one extra GET per library at cold start.
     */
    private val lastKnownRemoteTs: MutableMap<String, Long> = mutableMapOf()
    private val sweepMutex = Mutex()

    suspend fun run() = sweepMutex.withLock {
        val config = configStore.observe().value ?: run {
            logger.d(LogChannel.Playlists) { "run: no WebDAV config — skipping" }
            return@withLock
        }
        logger.d(LogChannel.Playlists) { "run: starting sweep baseUrl=${config.baseUrl}" }
        val syncer = syncerFactory(config, httpClient)
        val sources = sourceRepository.observeAll().first()
        val webSources = sources.filter { it.type.isWebSource }
        logger.d(LogChannel.Playlists) { "run: ${sources.size} sources total, ${webSources.size} web sources" }
        for (source in webSources) {
            val namespace = WebDavProgressRemoteFactory.webDavNamespace(source.type.name.lowercase())
            val libraryIds = libraryDao.libraryIdsForSource(source.id)
            logger.d(LogChannel.Playlists) { "run: source=${source.type} namespace=$namespace libraryIds=$libraryIds" }
            for (libraryId in libraryIds) {
                runCatching { syncLibrary(syncer, namespace, libraryId) }
                    .onFailure { logger.d(LogChannel.Playlists) { "run: syncLibrary($libraryId) failed: $it" } }
            }
        }
        logger.d(LogChannel.Playlists) { "run: sweep complete" }
    }

    private suspend fun syncLibrary(
        syncer: WebDavPlaylistSyncer,
        namespace: String,
        libraryId: String,
    ) {
        val playlistId = WebDavPlaylistSyncer.toReadPlaylistId(libraryId)
        val localTs = localToReadStore.lastUpdateMs(libraryId)
        // Fast path: if local timestamp equals the last-known remote timestamp from a prior run,
        // both sides are already in agreement — skip the GET entirely. This is the common steady-
        // state case (nothing changed on either side) and avoids redundant HTTP traffic on every
        // periodic tick.
        val cachedRemoteTs = lastKnownRemoteTs[libraryId]
        if (cachedRemoteTs != null && localTs == cachedRemoteTs) {
            logger.d(LogChannel.Playlists) { "syncLibrary: $libraryId skipping — in sync (ts=$localTs)" }
            return
        }
        val remote = syncer.pull(namespace, playlistId)
        logger.d(LogChannel.Playlists) { "syncLibrary: $libraryId remote=${remote?.lastUpdate} localTs=$localTs" }
        when {
            remote == null || localTs > remote.lastUpdate -> {
                // Local is authoritative — push to remote. Do NOT update local timestamp after the
                // push: the file body was written with localTs, but the server returns its own
                // Last-Modified which may be newer. Storing serverTs would make localTs > localTs on
                // the next sync and trigger another redundant push. We DO update the cache to reflect
                // that remote now holds our localTs so the next sweep can skip the GET.
                val localItems = localToReadStore.observeItemIds(libraryId).first().toList()
                logger.d(LogChannel.Playlists) { "syncLibrary: pushing $libraryId items=$localItems ts=$localTs" }
                val playlist = WebDavPlaylist(
                    id = playlistId,
                    name = "To Read",
                    libraryId = libraryId,
                    itemIds = localItems,
                    lastUpdate = localTs,
                )
                syncer.push(namespace, playlist)
                lastKnownRemoteTs[libraryId] = localTs
            }
            remote.lastUpdate > localTs -> {
                // Remote is authoritative — adopt remote items.
                logger.d(LogChannel.Playlists) { "syncLibrary: adopting remote $libraryId items=${remote.itemIds}" }
                localToReadStore.setAll(libraryId, remote.itemIds.toSet(), remote.lastUpdate)
                lastKnownRemoteTs[libraryId] = remote.lastUpdate
            }
            else -> {
                // Equal timestamps → no-op. Cache the timestamp so future sweeps skip the GET.
                logger.d(LogChannel.Playlists) { "syncLibrary: $libraryId no-op equal ts=$localTs" }
                lastKnownRemoteTs[libraryId] = localTs
            }
        }
    }
}

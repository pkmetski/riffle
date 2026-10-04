package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.sources.webdav.WebDavPlaylist
import com.riffle.core.sources.webdav.WebDavPlaylistSyncer
import com.riffle.core.sources.webdav.WebDavProgressRemoteFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first

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
) {
    suspend fun run() {
        val config = configStore.observe().value ?: return
        val syncer = syncerFactory(config, httpClient)
        val sources = sourceRepository.observeAll().first()
        for (source in sources) {
            if (!source.type.isWebSource) continue
            val namespace = WebDavProgressRemoteFactory.webDavNamespace(source.type.name.lowercase())
            val libraryIds = libraryDao.libraryIdsForSource(source.id)
            for (libraryId in libraryIds) {
                runCatching { syncLibrary(syncer, namespace, libraryId) }
            }
        }
    }

    private suspend fun syncLibrary(
        syncer: WebDavPlaylistSyncer,
        namespace: String,
        libraryId: String,
    ) {
        val playlistId = WebDavPlaylistSyncer.toReadPlaylistId(libraryId)
        val remote = syncer.pull(namespace, playlistId)
        val localTs = localToReadStore.lastUpdateMs(libraryId)
        when {
            remote == null || localTs > remote.lastUpdate -> {
                // Local is authoritative — push to remote. Do NOT update local timestamp after the
                // push: the file body was written with localTs, but the server returns its own
                // Last-Modified which may be newer. Storing serverTs would make localTs > localTs on
                // the next sync and trigger another redundant push.
                val localItems = localToReadStore.observeItemIds(libraryId).first().toList()
                val playlist = WebDavPlaylist(
                    id = playlistId,
                    name = "To Read",
                    libraryId = libraryId,
                    itemIds = localItems,
                    lastUpdate = localTs,
                )
                syncer.push(namespace, playlist)
            }
            remote.lastUpdate > localTs -> {
                // Remote is authoritative — adopt remote items.
                localToReadStore.setAll(libraryId, remote.itemIds.toSet(), remote.lastUpdate)
            }
            // Equal timestamps → no-op.
        }
    }
}

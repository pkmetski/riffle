package com.riffle.core.data

import com.riffle.core.database.LibraryDao
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.sources.webdav.WebDavPlaylist
import com.riffle.core.sources.webdav.WebDavPlaylistSyncer
import com.riffle.core.sources.webdav.WebDavProgressRemoteFactory
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
 */
class PlaylistSweep(
    private val sourceRepository: SourceRepository,
    private val libraryDao: LibraryDao,
    private val localToReadStore: LocalToReadStore,
    private val syncer: WebDavPlaylistSyncer,
    private val configStore: AnnotationSyncConfigStore,
) {
    suspend fun run() {
        configStore.observe().value ?: return
        val sources = sourceRepository.observeAll().first()
        for (source in sources) {
            if (!source.type.isWebSource) continue
            val namespace = WebDavProgressRemoteFactory.webDavNamespace(source.type.name.lowercase())
            val libraryIds = libraryDao.libraryIdsForSource(source.id)
            for (libraryId in libraryIds) {
                runCatching { syncLibrary(namespace, libraryId) }
            }
        }
    }

    private suspend fun syncLibrary(namespace: String, libraryId: String) {
        val playlistId = WebDavPlaylistSyncer.toReadPlaylistId(libraryId)
        val remote = syncer.pull(namespace, playlistId)
        val localTs = localToReadStore.lastUpdateMs(libraryId)
        when {
            remote == null || localTs > remote.lastUpdate -> {
                // Local is authoritative — push to remote.
                val localItems = localToReadStore.observeItemIds(libraryId).first().toList()
                val playlist = WebDavPlaylist(
                    id = playlistId,
                    name = "To Read",
                    libraryId = libraryId,
                    itemIds = localItems,
                    lastUpdate = localTs,
                )
                val serverTs = syncer.push(namespace, playlist)
                if (serverTs > 0L) {
                    localToReadStore.setAll(libraryId, localItems.toSet(), serverTs)
                }
            }
            remote.lastUpdate > localTs -> {
                // Remote is authoritative — adopt remote items.
                localToReadStore.setAll(libraryId, remote.itemIds.toSet(), remote.lastUpdate)
            }
            // Equal timestamps → no-op.
        }
    }
}

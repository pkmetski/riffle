package com.riffle.shared.reader

import com.riffle.core.common.FileStore
import com.riffle.core.data.IosItemFiles
import com.riffle.core.data.NS_CBZ_CACHE
import com.riffle.core.data.NS_CBZ_DOWNLOADS
import io.ktor.utils.io.ByteReadChannel

/**
 * The CBZ repository's view of the two on-disk stores — `<namespace>/<sourceId>/<itemId>.cbz`
 * under [NS_CBZ_DOWNLOADS] (user-pinned) and [NS_CBZ_CACHE] (background cache). Path resolution
 * and the file primitives are [IosItemFiles], the same layout `IosDownloadsRepositoryImpl`,
 * `IosContentCacheArtifactScannerImpl` and `IosLibraryItemOfflineAvailabilityImpl` read (#1101).
 */
internal class IosCbzFiles(private val fileStore: FileStore) {

    fun downloadPath(sourceId: String, itemId: String): String =
        IosItemFiles.path(fileStore, NS_CBZ_DOWNLOADS, sourceId, itemId, EXTENSION)

    fun cachePath(sourceId: String, itemId: String): String =
        IosItemFiles.path(fileStore, NS_CBZ_CACHE, sourceId, itemId, EXTENSION)

    fun exists(path: String): Boolean = IosItemFiles.exists(path)

    fun readBytes(path: String): ByteArray? = IosItemFiles.readBytes(path)

    fun writeBytes(path: String, bytes: ByteArray): Boolean = IosItemFiles.writeBytes(path, bytes)

    suspend fun writeChannel(
        path: String,
        channel: ByteReadChannel,
        totalBytes: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Boolean = IosItemFiles.writeChannel(path, channel, totalBytes, onProgress)

    fun delete(path: String) = IosItemFiles.delete(path)

    fun move(from: String, to: String): Boolean = IosItemFiles.move(from, to)

    companion object {
        const val EXTENSION = ".cbz"
    }
}

package com.riffle.core.data.localfiles

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.LocalFilesFolderEntity
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUUID
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
class IosLocalFilesFolderRepository(
    private val folderDao: LocalFilesFolderDao,
    private val libraryDao: LibraryDao,
    private val fileFolderDao: LocalFilesFileFolderDao,
) {

    suspend fun addFolder(sourceId: String, folderUri: FolderUri): String {
        val uriStr = folderUri.value
        val displayName = uriStr.substringAfterLast('/').ifBlank { uriStr }
        val existing = folderDao.forSource(sourceId).firstOrNull { it.treeUri == uriStr }
        val libraryId = existing?.libraryId ?: (LOCAL_FILES_LIBRARY_ID_PREFIX + NSUUID().UUIDString())
        folderDao.upsert(
            LocalFilesFolderEntity(
                sourceId = sourceId,
                treeUri = uriStr,
                displayName = displayName,
                addedAtEpochMs = existing?.addedAtEpochMs ?: nowMs(),
                libraryId = libraryId,
            ),
        )
        libraryDao.upsertAll(
            listOf(LibraryEntity(id = libraryId, name = displayName, mediaType = "book", sourceId = sourceId)),
        )
        return libraryId
    }

    suspend fun removeFolder(sourceId: String, treeUri: String) {
        val folder = folderDao.forSource(sourceId).firstOrNull { it.treeUri == treeUri }
        fileFolderDao.deleteFolder(sourceId, treeUri)
        folderDao.delete(sourceId, treeUri)
        folder?.let { libraryDao.deleteById(sourceId, it.libraryId) }
    }

    private fun nowMs(): Long = time(null).toLong() * 1000L

    companion object {
        const val LOCAL_FILES_LIBRARY_ID_PREFIX = "local:folder:"
    }
}

package com.riffle.core.data.localfiles

import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFileFolderEntity
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.LocalFilesFolderEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// In-memory stand-ins for the Room/SQLDelight DAOs the Local Files pipeline writes through.
// Shared by every iOS local-files test so the scanner, the folder repository and the source
// installer are all driven against the same storage semantics.

internal class InMemoryLocalFilesFolderDao : LocalFilesFolderDao {
    private val store = mutableMapOf<Pair<String, String>, LocalFilesFolderEntity>()
    override suspend fun upsert(entity: LocalFilesFolderEntity) {
        store[entity.sourceId to entity.treeUri] = entity
    }
    override suspend fun forSource(sourceId: String): List<LocalFilesFolderEntity> =
        store.values.filter { it.sourceId == sourceId }.sortedBy { it.addedAtEpochMs }
    override fun observeForSource(sourceId: String): Flow<List<LocalFilesFolderEntity>> =
        MutableStateFlow(store.values.filter { it.sourceId == sourceId })
    override suspend fun getByLibraryId(sourceId: String, libraryId: String): LocalFilesFolderEntity? =
        store.values.firstOrNull { it.sourceId == sourceId && it.libraryId == libraryId }
    override suspend fun delete(sourceId: String, treeUri: String) {
        store.remove(sourceId to treeUri)
    }
}

internal open class InMemoryLocalFilesFileDao : LocalFilesFileDao {
    val rows = mutableMapOf<Pair<String, String>, LocalFilesFileEntity>()
    override suspend fun upsert(entity: LocalFilesFileEntity) {
        rows[entity.sourceId to entity.sourceItemId] = entity
    }
    override suspend fun findById(sourceId: String, sourceItemId: String): LocalFilesFileEntity? =
        rows[sourceId to sourceItemId]
    override suspend fun forSource(sourceId: String): List<LocalFilesFileEntity> =
        rows.values.filter { it.sourceId == sourceId }
    override suspend fun getForItems(sourceId: String, sourceItemIds: List<String>): List<LocalFilesFileEntity> =
        rows.values.filter { it.sourceId == sourceId && it.sourceItemId in sourceItemIds }
    override suspend fun touchLastSeen(sourceId: String, sourceItemId: String, seenAt: Long) {
        rows[sourceId to sourceItemId]?.let { rows[sourceId to sourceItemId] = it.copy(lastSeenAtEpochMs = seenAt) }
    }
    override suspend fun updateDisplayName(sourceId: String, sourceItemId: String, displayName: String) {
        rows[sourceId to sourceItemId]?.let { rows[sourceId to sourceItemId] = it.copy(displayName = displayName) }
    }
    override suspend fun delete(sourceId: String, sourceItemId: String) {
        rows.remove(sourceId to sourceItemId)
    }
}

internal class InMemoryLocalFilesFileFolderDao(
    private val fileDao: InMemoryLocalFilesFileDao,
) : LocalFilesFileFolderDao {
    val rows = mutableMapOf<Triple<String, String, String>, LocalFilesFileFolderEntity>()
    override suspend fun upsert(entity: LocalFilesFileFolderEntity) {
        rows[Triple(entity.sourceId, entity.sourceItemId, entity.folderTreeUri)] = entity
    }
    override suspend fun forFile(sourceId: String, sourceItemId: String): List<LocalFilesFileFolderEntity> =
        rows.values.filter { it.sourceId == sourceId && it.sourceItemId == sourceItemId }
    override suspend fun forFolder(sourceId: String, folderTreeUri: String): List<LocalFilesFileFolderEntity> =
        rows.values.filter { it.sourceId == sourceId && it.folderTreeUri == folderTreeUri }
    override suspend fun itemIdsInFolder(sourceId: String, folderTreeUri: String): List<String> =
        forFolder(sourceId, folderTreeUri).map { it.sourceItemId }
    override suspend fun stale(sourceId: String, scanStart: Long): List<LocalFilesFileFolderEntity> =
        rows.values.filter { it.sourceId == sourceId && it.lastSeenAtEpochMs < scanStart }
    override suspend fun delete(sourceId: String, sourceItemId: String, folderTreeUri: String) {
        rows.remove(Triple(sourceId, sourceItemId, folderTreeUri))
    }
    override suspend fun deleteFolder(sourceId: String, folderTreeUri: String) {
        rows.keys.filter { it.first == sourceId && it.third == folderTreeUri }.forEach { rows.remove(it) }
    }
    override suspend fun deleteFile(sourceId: String, sourceItemId: String) {
        rows.keys.filter { it.first == sourceId && it.second == sourceItemId }.forEach { rows.remove(it) }
    }
    override suspend fun orphanedFiles(sourceId: String): List<LocalFilesFileEntity> {
        val liveItemIds = rows.values.filter { it.sourceId == sourceId }.map { it.sourceItemId }.toHashSet()
        return fileDao.rows.values.filter { it.sourceId == sourceId && it.sourceItemId !in liveItemIds }
    }
}

internal class InMemoryLibraryDao : LibraryDao {
    private val store = mutableMapOf<Pair<String, String>, LibraryEntity>()
    override fun observeBySourceId(sourceId: String): Flow<List<LibraryEntity>> =
        MutableStateFlow(store.values.filter { it.sourceId == sourceId })
    override suspend fun libraryIdsForSource(sourceId: String): List<String> =
        store.values.filter { it.sourceId == sourceId }.map { it.id }
    override suspend fun getById(sourceId: String, libraryId: String): LibraryEntity? =
        store[sourceId to libraryId]
    override suspend fun upsertAll(libraries: List<LibraryEntity>) {
        libraries.forEach { store[it.sourceId to it.id] = it }
    }
    override suspend fun deleteBySourceId(sourceId: String) {
        store.keys.filter { it.first == sourceId }.forEach { store.remove(it) }
    }
    override suspend fun deleteById(sourceId: String, libraryId: String) {
        store.remove(sourceId to libraryId)
    }
    override suspend fun setUnsupported(sourceId: String, libraryId: String, isUnsupported: Boolean) {
        store[sourceId to libraryId]?.let { store[sourceId to libraryId] = it.copy(isUnsupported = isUnsupported) }
    }
}

package com.riffle.core.data.localfiles

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFileFolderEntity
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.domain.DispatcherProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.withContext
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
class IosLocalFilesScanner(
    private val folderDao: LocalFilesFolderDao,
    private val fileDao: LocalFilesFileDao,
    private val fileFolderDao: LocalFilesFileFolderDao,
    private val libraryItemDao: LibraryItemDao,
    private val walker: IosFolderWalker,
    private val copyIn: IosCopyInService,
    private val dispatchers: DispatcherProvider,
) {

    data class ScanReport(
        val added: Int,
        val refreshed: Int,
        val removed: Int,
        val failures: List<ScanFailure>,
    )

    data class ScanFailure(val displayName: String, val reason: String)

    suspend fun scan(sourceId: String): ScanReport = withContext(dispatchers.io) {
        val scanStart = nowMs()
        val folders = folderDao.forSource(sourceId)
        var added = 0
        var refreshed = 0
        val failures = mutableListOf<ScanFailure>()
        var allSucceeded = true

        for (folder in folders) {
            val files = try {
                walker.walk(folder.treeUri)
            } catch (e: Exception) {
                failures += ScanFailure(folder.displayName, "walk-failed: ${e.message ?: "unknown"}")
                allSucceeded = false
                continue
            }
            for (file in files) {
                try {
                    when (ingest(sourceId, folder.treeUri, folder.libraryId, file, scanStart)) {
                        Outcome.ADDED -> added++
                        Outcome.REFRESHED -> refreshed++
                        Outcome.SKIPPED -> Unit
                    }
                } catch (e: Exception) {
                    failures += ScanFailure(file.displayName, "ingest-failed: ${e.message ?: "unknown"}")
                    allSucceeded = false
                }
            }
        }

        val removed = if (allSucceeded) sweepStale(sourceId, scanStart) else 0
        ScanReport(added, refreshed, removed, failures)
    }

    private suspend fun sweepStale(sourceId: String, scanStart: Long): Int {
        val stale = fileFolderDao.stale(sourceId, scanStart)
        for (m in stale) fileFolderDao.delete(m.sourceId, m.sourceItemId, m.folderTreeUri)
        val orphans = fileFolderDao.orphanedFiles(sourceId)
        for (row in orphans) {
            libraryItemDao.deleteById(row.sourceId, row.sourceItemId)
            copyIn.deleteBook(row.sourceId, row.sourceItemId)
            if (row.coverPath != null) copyIn.deleteCover(row.sourceId, row.sourceItemId)
            fileDao.delete(row.sourceId, row.sourceItemId)
        }
        return orphans.size
    }

    private enum class Outcome { ADDED, REFRESHED, SKIPPED }

    private suspend fun ingest(
        sourceId: String,
        folderTreeUri: String,
        folderLibraryId: String,
        file: IosWalkedFile,
        scanStart: Long,
    ): Outcome {
        val head = readHead(file.path)
        val kind = FileClassifier.classify(file.displayName, head)
        if (kind == FileClassifier.Kind.UNKNOWN) return Outcome.SKIPPED

        val identity = IosIdentityHasher.hash(head, file.sizeBytes)
        val existing = fileDao.findById(sourceId, identity)
        if (existing != null) {
            fileDao.touchLastSeen(sourceId, identity, scanStart)
            fileDao.updateDisplayName(sourceId, identity, file.displayName)
            fileFolderDao.upsert(
                LocalFilesFileFolderEntity(
                    sourceId = sourceId,
                    sourceItemId = identity,
                    folderTreeUri = folderTreeUri,
                    lastSeenAtEpochMs = scanStart,
                ),
            )
            libraryItemDao.updateLibraryId(sourceId, identity, folderLibraryId)
            return Outcome.REFRESHED
        }

        val extension = when (kind) {
            FileClassifier.Kind.EPUB -> "epub"
            FileClassifier.Kind.PDF -> "pdf"
            FileClassifier.Kind.CBZ -> "cbz"
            FileClassifier.Kind.UNKNOWN -> return Outcome.SKIPPED
        }

        val copiedPath = copyIn.copyBook(sourceId, identity, extension, file.path)
        val title = file.displayName.substringBeforeLast('.').ifBlank { file.displayName }
        libraryItemDao.upsertAll(
            listOf(
                LibraryItemEntity(
                    sourceId = sourceId,
                    id = identity,
                    libraryId = folderLibraryId,
                    title = title,
                    author = "",
                    coverUrl = null,
                    readingProgress = 0f,
                    ebookFormat = extension,
                    addedAt = scanStart,
                ),
            ),
        )
        fileDao.upsert(
            LocalFilesFileEntity(
                sourceId = sourceId,
                sourceItemId = identity,
                originalUri = file.path,
                copiedPath = copiedPath,
                coverPath = null,
                format = extension,
                sizeBytes = file.sizeBytes,
                mtimeEpochMs = file.mtimeEpochMs,
                lastSeenAtEpochMs = scanStart,
                displayName = file.displayName,
            ),
        )
        fileFolderDao.upsert(
            LocalFilesFileFolderEntity(
                sourceId = sourceId,
                sourceItemId = identity,
                folderTreeUri = folderTreeUri,
                lastSeenAtEpochMs = scanStart,
            ),
        )
        return Outcome.ADDED
    }

    private fun readHead(path: String): ByteArray {
        val f = fopen(path, "rb") ?: return ByteArray(0)
        return try {
            val buf = ByteArray(HEAD_BYTES.toInt())
            val n = buf.usePinned { pinned ->
                fread(pinned.addressOf(0), 1.toULong(), HEAD_BYTES.toULong(), f)
            }
            buf.copyOf(n.toInt())
        } finally {
            fclose(f)
        }
    }

    private fun nowMs(): Long = time(null).toLong() * 1000L

    companion object {
        private const val HEAD_BYTES = 64 * 1024L
    }
}

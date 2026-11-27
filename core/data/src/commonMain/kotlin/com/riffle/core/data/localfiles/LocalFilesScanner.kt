package com.riffle.core.data.localfiles

import com.riffle.core.common.Clock
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFileFolderEntity
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.EpubMetadata
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.use

// Idempotently ingests a LocalFiles source's folders; the stale sweep only runs after a fully-clean
// walk so a transient folder error can't delete still-present books. Not thread-safe: one scan per source.
class LocalFilesScanner(
    private val folderDao: LocalFilesFolderDao,
    private val fileDao: LocalFilesFileDao,
    private val fileFolderDao: LocalFilesFileFolderDao,
    private val libraryItemDao: LibraryItemDao,
    private val walker: FolderWalker,
    private val copyIn: CopyInService,
    private val metadataReader: LocalBookMetadataReader,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
    private val logger: Logger,
) {

    data class ScanReport(
        val added: Int,
        val refreshed: Int,
        val removed: Int,
        val failures: List<ScanFailure>,
    )

    data class ScanFailure(val displayName: String, val reason: String)

    suspend fun scan(sourceId: String): ScanReport = withContext(dispatchers.io) {
        val scanStart = clock.nowMs()
        val folders = folderDao.forSource(sourceId)
        logger.d(LogChannel.LocalFiles) { "scan start sourceId=$sourceId folders=${folders.size}" }
        var added = 0
        var refreshed = 0
        val failures = mutableListOf<ScanFailure>()
        // A single transient walk/ingest failure suppresses the sweep for this pass so a
        // DocumentsProvider hiccup does not wipe every previously-ingested book in the folder.
        var completed = true

        for (folder in folders) {
            val files = try {
                walker.walk(folder.treeUri)
            } catch (e: Exception) {
                logger.d(LogChannel.LocalFiles, e) { "walk failed for ${folder.displayName}" }
                failures += ScanFailure(folder.displayName, "walk-failed: ${e.message ?: e::class.simpleName}")
                completed = false
                continue
            }
            logger.d(LogChannel.LocalFiles) {
                "walked folder=${folder.displayName} files=${files.size} " +
                    "sample=${files.take(3).joinToString { it.displayName }}"
            }
            for (file in files) {
                val outcome = try {
                    ingest(sourceId, folder.treeUri, folder.libraryId, file, scanStart)
                } catch (e: Exception) {
                    failures += ScanFailure(file.displayName, "ingest-failed: ${e.message ?: e::class.simpleName}")
                    completed = false
                    continue
                }
                when (outcome) {
                    Outcome.ADDED -> added += 1
                    Outcome.REFRESHED -> refreshed += 1
                    Outcome.SKIPPED -> {}
                }
            }
        }

        val removed = if (completed) sweepStale(sourceId, scanStart) else 0
        ScanReport(added = added, refreshed = refreshed, removed = removed, failures = failures)
    }

    private suspend fun sweepStale(sourceId: String, scanStart: Long): Int {
        val staleMemberships = fileFolderDao.stale(sourceId, scanStart)
        for (m in staleMemberships) {
            fileFolderDao.delete(m.sourceId, m.sourceItemId, m.folderTreeUri)
        }
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
        file: WalkedFile,
        scanStart: Long,
    ): Outcome {
        val head = readHead(file)
        val kind = FileClassifier.classify(file.displayName, head)
        logger.d(LogChannel.LocalFiles) {
            "classify name=${file.displayName} kind=$kind headBytes=${head.size}"
        }
        if (kind == FileClassifier.Kind.UNKNOWN) return Outcome.SKIPPED

        val identity = IdentityHasher.hash(head, file.sizeBytes)
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
            FileClassifier.Kind.EPUB -> EbookFormat.STORAGE_EPUB
            FileClassifier.Kind.PDF -> EbookFormat.STORAGE_PDF
            FileClassifier.Kind.CBZ -> EbookFormat.STORAGE_CBZ
            FileClassifier.Kind.UNKNOWN -> return Outcome.SKIPPED
        }
        val copiedPath = copyIn.copyBook(sourceId, identity, extension, file.openSource())

        val (item, coverPath) = when (kind) {
            FileClassifier.Kind.EPUB -> buildEpubItem(sourceId, identity, folderLibraryId, file, copiedPath)
            FileClassifier.Kind.PDF -> buildPdfItem(sourceId, identity, folderLibraryId, file, copiedPath)
            FileClassifier.Kind.CBZ -> buildCbzItem(sourceId, identity, folderLibraryId, file, copiedPath)
            FileClassifier.Kind.UNKNOWN -> return Outcome.SKIPPED
        }
        libraryItemDao.upsertAll(listOf(item))
        fileDao.upsert(
            LocalFilesFileEntity(
                sourceId = sourceId,
                sourceItemId = identity,
                originalUri = file.originalUri,
                copiedPath = copiedPath,
                coverPath = coverPath,
                format = ebookFormatOf(kind),
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

    private suspend fun buildEpubItem(
        sourceId: String,
        identity: String,
        folderLibraryId: String,
        file: WalkedFile,
        copiedPath: String,
    ): Pair<LibraryItemEntity, String?> {
        val metadata = metadataReader.epub(copiedPath)
        val coverBytes = metadata.coverBytes
        val coverPath = if (coverBytes != null) {
            copyIn.writeCover(sourceId, identity, metadata.coverExtension ?: "jpg", coverBytes)
        } else {
            null
        }
        return libraryItemFromEpub(sourceId, identity, folderLibraryId, file, metadata, coverPath) to coverPath
    }

    private suspend fun buildPdfItem(
        sourceId: String,
        identity: String,
        folderLibraryId: String,
        file: WalkedFile,
        copiedPath: String,
    ): Pair<LibraryItemEntity, String?> {
        val metadata = metadataReader.pdf(copiedPath)
        val entity = LibraryItemEntity(
            sourceId = sourceId,
            id = identity,
            libraryId = folderLibraryId,
            title = metadata.title?.ifBlank { null } ?: stripExtension(file.displayName),
            author = metadata.author?.ifBlank { null } ?: "",
            coverUrl = null,
            readingProgress = 0f,
            ebookFormat = EBOOK_FORMAT_PDF,
            description = metadata.subject,
            addedAt = clock.nowMs(),
        )
        return entity to null
    }

    private suspend fun buildCbzItem(
        sourceId: String,
        identity: String,
        folderLibraryId: String,
        file: WalkedFile,
        copiedPath: String,
    ): Pair<LibraryItemEntity, String?> {
        val metadata = metadataReader.cbz(copiedPath)
        val coverPath = metadata.coverBytes?.let { bytes ->
            copyIn.writeCover(sourceId, identity, metadata.coverExtension ?: "jpg", bytes)
        }
        val entity = LibraryItemEntity(
            sourceId = sourceId,
            id = identity,
            libraryId = folderLibraryId,
            title = stripExtension(file.displayName),
            author = "",
            coverUrl = coverPath.toFileUrl(),
            readingProgress = 0f,
            ebookFormat = EBOOK_FORMAT_CBZ,
            addedAt = clock.nowMs(),
            pageCount = metadata.pageCount.takeIf { it > 0 },
        )
        return entity to coverPath
    }

    private fun libraryItemFromEpub(
        sourceId: String,
        identity: String,
        folderLibraryId: String,
        file: WalkedFile,
        metadata: EpubMetadata,
        coverPath: String?,
    ): LibraryItemEntity = LibraryItemEntity(
        sourceId = sourceId,
        id = identity,
        libraryId = folderLibraryId,
        title = metadata.title?.ifBlank { null } ?: stripExtension(file.displayName),
        author = metadata.author?.ifBlank { null } ?: "",
        coverUrl = coverPath.toFileUrl(),
        readingProgress = 0f,
        ebookFormat = EBOOK_FORMAT_EPUB,
        description = metadata.description,
        seriesName = metadata.seriesName,
        seriesSequence = metadata.seriesSequence,
        publishedYear = metadata.publishedYear,
        genres = metadata.genres.joinToString(","),
        publisher = metadata.publisher,
        language = metadata.language,
        isbn = metadata.isbn,
        asin = metadata.asin,
        addedAt = clock.nowMs(),
    )

    // Unified cross-platform file URI (Coil loads both `file://` and `file:/` forms); iOS already
    // used this form, Android previously used File.toURI() which emits a single-slash variant.
    private fun String?.toFileUrl(): String? = this?.let { "file://$it" }

    private fun ebookFormatOf(kind: FileClassifier.Kind): String = when (kind) {
        FileClassifier.Kind.EPUB -> EBOOK_FORMAT_EPUB
        FileClassifier.Kind.PDF -> EBOOK_FORMAT_PDF
        FileClassifier.Kind.CBZ -> EBOOK_FORMAT_CBZ
        FileClassifier.Kind.UNKNOWN -> EBOOK_FORMAT_UNSUPPORTED
    }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    private suspend fun readHead(file: WalkedFile): ByteArray = file.openSource().use { source ->
        val buffer = Buffer()
        var total = 0L
        while (total < HEAD_BYTES) {
            val read = source.read(buffer, HEAD_BYTES - total)
            if (read == -1L) break
            total += read
        }
        buffer.readByteArray()
    }

    companion object {
        const val EBOOK_FORMAT_EPUB: String = EbookFormat.STORAGE_EPUB
        const val EBOOK_FORMAT_PDF: String = EbookFormat.STORAGE_PDF
        const val EBOOK_FORMAT_CBZ: String = EbookFormat.STORAGE_CBZ
        const val EBOOK_FORMAT_UNSUPPORTED: String = EbookFormat.STORAGE_UNSUPPORTED
        private const val HEAD_BYTES: Long = 64L * 1024L
    }
}

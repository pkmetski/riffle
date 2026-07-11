package com.riffle.core.data.localfiles

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFileFolderEntity
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.domain.Clock
import com.riffle.core.domain.EbookFormat
import com.riffle.core.domain.EpubMetadata
import com.riffle.core.domain.EpubMetadataExtractor
import com.riffle.core.domain.PdfMetadata
import com.riffle.core.domain.PdfMetadataExtractor
import com.riffle.core.domain.comic.ComicMetadata
import com.riffle.core.domain.comic.ComicMetadataExtractor
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Walks the LocalFiles Source's configured folders, classifies supported book files (see
 * [FileClassifier.Kind]) by extension + magic bytes, computes a content-identity hash, and idempotently upserts:
 *   - a `library_items` row per unique file (title/author/…, cover URL where extractable), with
 *     `libraryId` set to whichever folder-library currently contains it (if a file lives in
 *     several folders, the row's libraryId names any one of them — folder-scoped browsing goes
 *     through `local_files_file_folders`).
 *   - a `local_files_files` row per unique file (originalUri, copiedPath, coverPath, lastSeenAt).
 *   - one `local_files_file_folders` membership row per (file, folder) pairing found this pass.
 *
 * A single scan pass runs across *all* configured folders of the source. After a fully-clean walk,
 * membership rows whose `lastSeenAtEpochMs` predates `scanStart` are hard-deleted; a file that
 * loses its last membership takes the `library_items` row, the file row, and the copied bytes with
 * it. This unifies "file removed from folder", "folder no longer configured", and "folder
 * permission revoked" — all surface as absence during the walk.
 *
 * Not thread-safe: only one scan should be in-flight per source at a time.
 */
class LocalFilesScanner @Inject constructor(
    private val folderDao: LocalFilesFolderDao,
    private val fileDao: LocalFilesFileDao,
    private val fileFolderDao: LocalFilesFileFolderDao,
    private val libraryItemDao: LibraryItemDao,
    private val walker: FolderWalker,
    private val copyIn: CopyInService,
    private val pdfMetadata: PdfMetadataExtractor,
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

    suspend fun scan(sourceId: String): ScanReport = withContext(Dispatchers.IO) {
        val scanStart = clock.nowMs()
        val folders = folderDao.forSource(sourceId)
        logger.d(LogChannel.LocalFiles) { "scan start sourceId=$sourceId folders=${folders.size}" }
        var added = 0
        var refreshed = 0
        val failures = mutableListOf<ScanFailure>()
        // We only prune stale rows when every folder walked cleanly AND every ingest succeeded.
        // A single transient walk/ingest failure suppresses the sweep for this pass — otherwise a
        // DocumentsProvider hiccup on a folder full of previously-ingested books would wipe every
        // one of them (their lastSeenAt never gets touched because the walk aborts). We accept
        // that a mixed-success scan doesn't reclaim deleted books until the next fully-clean scan.
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

    /**
     * Prunes junction rows (file-in-folder memberships) not touched by this scan, plus any file
     * row whose last membership just disappeared. Returns the number of removed **files** — files
     * that lost their last folder and were fully evicted — not the number of removed memberships.
     * Matches the pre-junction contract callers already assert.
     */
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
        val head = file.openStream().use { s ->
            val buf = ByteArray(HEAD_BYTES)
            var total = 0
            while (total < buf.size) {
                val r = s.read(buf, total, buf.size - total)
                if (r <= 0) break
                total += r
            }
            if (total == buf.size) buf else buf.copyOf(total)
        }
        val kind = FileClassifier.classify(file.displayName, head)
        logger.d(LogChannel.LocalFiles) {
            "classify name=${file.displayName} kind=$kind headBytes=${head.size} " +
                "head4=${head.take(4).joinToString(",") { "%02x".format(it) }}"
        }
        if (kind == FileClassifier.Kind.UNKNOWN) return Outcome.SKIPPED

        val identity = IdentityHasher.hash(head, file.sizeBytes)
        val existing = fileDao.findById(sourceId, identity)
        if (existing != null) {
            fileDao.touchLastSeen(sourceId, identity, scanStart)
            fileFolderDao.upsert(
                LocalFilesFileFolderEntity(
                    sourceId = sourceId,
                    sourceItemId = identity,
                    folderTreeUri = folderTreeUri,
                    lastSeenAtEpochMs = scanStart,
                ),
            )
            // Retag the library_items compatibility hint at the currently-walked folder so
            // library_items.libraryId always names a still-configured folder library. Without
            // this, removing the "home" folder of a shared book would leave the row pointing at
            // a deleted LibraryEntity even though the book still lives in another folder.
            libraryItemDao.updateLibraryId(sourceId, identity, folderLibraryId)
            return Outcome.REFRESHED
        }

        val extension = when (kind) {
            FileClassifier.Kind.EPUB -> EbookFormat.STORAGE_EPUB
            FileClassifier.Kind.PDF -> EbookFormat.STORAGE_PDF
            FileClassifier.Kind.CBZ -> EbookFormat.STORAGE_CBZ
            FileClassifier.Kind.UNKNOWN -> return Outcome.SKIPPED
        }
        val copied = file.openStream().use { s -> copyIn.copyBook(sourceId, identity, extension, s) }

        val (item, coverPath) = when (kind) {
            FileClassifier.Kind.EPUB -> buildEpubItem(sourceId, identity, folderLibraryId, file, copied)
            FileClassifier.Kind.PDF -> buildPdfItem(sourceId, identity, folderLibraryId, file, copied)
            FileClassifier.Kind.CBZ -> buildCbzItem(sourceId, identity, folderLibraryId, file, copied)
            FileClassifier.Kind.UNKNOWN -> return Outcome.SKIPPED
        }
        libraryItemDao.upsertAll(listOf(item))
        fileDao.upsert(
            LocalFilesFileEntity(
                sourceId = sourceId,
                sourceItemId = identity,
                originalUri = file.originalUri,
                copiedPath = copied.absolutePath,
                coverPath = coverPath?.absolutePath,
                format = ebookFormatOf(kind),
                sizeBytes = file.sizeBytes,
                mtimeEpochMs = file.mtimeEpochMs,
                lastSeenAtEpochMs = scanStart,
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
        copied: java.io.File,
    ): Pair<LibraryItemEntity, java.io.File?> {
        val metadata = EpubMetadataExtractor.extract(copied)
        val coverBytes = metadata.coverBytes
        val coverFile = if (coverBytes != null) {
            copyIn.writeCover(sourceId, identity, metadata.coverExtension ?: "jpg", coverBytes)
        } else null
        return libraryItemFromEpub(sourceId, identity, folderLibraryId, file, metadata, coverFile) to coverFile
    }

    private suspend fun buildPdfItem(
        sourceId: String,
        identity: String,
        folderLibraryId: String,
        file: WalkedFile,
        copied: java.io.File,
    ): Pair<LibraryItemEntity, java.io.File?> {
        val metadata = try { pdfMetadata.extract(copied) } catch (_: Exception) { PdfMetadata.EMPTY }
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
        copied: java.io.File,
    ): Pair<LibraryItemEntity, java.io.File?> {
        val metadata = ComicMetadataExtractor.extract(copied)
        val coverFile = metadata.coverBytes?.let { bytes ->
            copyIn.writeCover(sourceId, identity, metadata.coverExtension ?: "jpg", bytes)
        }
        val entity = LibraryItemEntity(
            sourceId = sourceId,
            id = identity,
            libraryId = folderLibraryId,
            title = stripExtension(file.displayName),
            author = "",
            coverUrl = coverFile?.toURI()?.toString(),
            readingProgress = 0f,
            ebookFormat = EBOOK_FORMAT_CBZ,
            addedAt = clock.nowMs(),
            pageCount = metadata.pageCount.takeIf { it > 0 },
        )
        return entity to coverFile
    }

    private fun libraryItemFromEpub(
        sourceId: String,
        identity: String,
        folderLibraryId: String,
        file: WalkedFile,
        metadata: EpubMetadata,
        coverFile: java.io.File?,
    ): LibraryItemEntity = LibraryItemEntity(
        sourceId = sourceId,
        id = identity,
        libraryId = folderLibraryId,
        title = metadata.title?.ifBlank { null } ?: stripExtension(file.displayName),
        author = metadata.author?.ifBlank { null } ?: "",
        coverUrl = coverFile?.toURI()?.toString(),
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

    companion object {
        const val EBOOK_FORMAT_EPUB: String = EbookFormat.STORAGE_EPUB
        const val EBOOK_FORMAT_PDF: String = EbookFormat.STORAGE_PDF
        const val EBOOK_FORMAT_CBZ: String = EbookFormat.STORAGE_CBZ
        const val EBOOK_FORMAT_UNSUPPORTED: String = EbookFormat.STORAGE_UNSUPPORTED
        private const val HEAD_BYTES: Int = 64 * 1024
    }
}

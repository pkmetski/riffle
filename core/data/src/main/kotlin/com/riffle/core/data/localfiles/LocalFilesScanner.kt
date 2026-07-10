package com.riffle.core.data.localfiles

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.database.LocalFilesFileDao
import com.riffle.core.database.LocalFilesFileEntity
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.domain.Clock
import com.riffle.core.domain.EpubMetadata
import com.riffle.core.domain.EpubMetadataExtractor
import com.riffle.core.domain.PdfMetadata
import com.riffle.core.domain.PdfMetadataExtractor
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Walks the LocalFiles Source's configured folders, classifies EPUB/PDF files by extension +
 * magic bytes, computes a content-identity hash, and idempotently upserts:
 *   - a `library_items` row per unique file (title/author/…, cover URL where extractable),
 *   - a `local_files_files` row per unique file (originalUri, copiedPath, coverPath, lastSeenAt).
 *
 * A single scan pass runs across *all* configured folders of the source; rows whose
 * `lastSeenAtEpochMs` predates `scanStart` after the pass are hard-deleted along with their copied
 * bytes and library rows. This unifies "file removed from folder" with "folder permission revoked"
 * — both surface as absence during the walk.
 *
 * Not thread-safe: only one scan should be in-flight per source at a time.
 */
class LocalFilesScanner @Inject constructor(
    private val folderDao: LocalFilesFolderDao,
    private val fileDao: LocalFilesFileDao,
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
                    ingest(sourceId, folder.treeUri, file, scanStart)
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
        val stale = fileDao.stale(sourceId, scanStart)
        for (row in stale) {
            libraryItemDao.deleteById(row.sourceId, row.sourceItemId)
            copyIn.deleteBook(row.sourceId, row.sourceItemId)
            if (row.coverPath != null) copyIn.deleteCover(row.sourceId, row.sourceItemId)
            fileDao.delete(row.sourceId, row.sourceItemId)
        }
        return stale.size
    }

    private enum class Outcome { ADDED, REFRESHED, SKIPPED }

    private suspend fun ingest(
        sourceId: String,
        folderTreeUri: String,
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
            fileDao.touchLastSeen(sourceId, identity, folderTreeUri, scanStart)
            return Outcome.REFRESHED
        }

        val extension = when (kind) {
            FileClassifier.Kind.EPUB -> "epub"
            FileClassifier.Kind.PDF -> "pdf"
            FileClassifier.Kind.UNKNOWN -> return Outcome.SKIPPED
        }
        val copied = file.openStream().use { s -> copyIn.copyBook(sourceId, identity, extension, s) }

        val (item, coverPath) = when (kind) {
            FileClassifier.Kind.EPUB -> buildEpubItem(sourceId, identity, file, copied)
            FileClassifier.Kind.PDF -> buildPdfItem(sourceId, identity, file, copied)
            FileClassifier.Kind.UNKNOWN -> return Outcome.SKIPPED
        }
        libraryItemDao.upsertAll(listOf(item))
        fileDao.upsert(
            LocalFilesFileEntity(
                sourceId = sourceId,
                sourceItemId = identity,
                folderTreeUri = folderTreeUri,
                originalUri = file.originalUri,
                copiedPath = copied.absolutePath,
                coverPath = coverPath?.absolutePath,
                format = ebookFormatOf(kind),
                sizeBytes = file.sizeBytes,
                mtimeEpochMs = file.mtimeEpochMs,
                lastSeenAtEpochMs = scanStart,
            ),
        )
        return Outcome.ADDED
    }

    private suspend fun buildEpubItem(
        sourceId: String,
        identity: String,
        file: WalkedFile,
        copied: java.io.File,
    ): Pair<LibraryItemEntity, java.io.File?> {
        val metadata = EpubMetadataExtractor.extract(copied)
        val coverBytes = metadata.coverBytes
        val coverFile = if (coverBytes != null) {
            copyIn.writeCover(sourceId, identity, metadata.coverExtension ?: "jpg", coverBytes)
        } else null
        return libraryItemFromEpub(sourceId, identity, file, metadata, coverFile) to coverFile
    }

    private suspend fun buildPdfItem(
        sourceId: String,
        identity: String,
        file: WalkedFile,
        copied: java.io.File,
    ): Pair<LibraryItemEntity, java.io.File?> {
        val metadata = try { pdfMetadata.extract(copied) } catch (_: Exception) { PdfMetadata.EMPTY }
        val entity = LibraryItemEntity(
            sourceId = sourceId,
            id = identity,
            libraryId = LocalFilesCatalog.LOCAL_ROOT_ID,
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

    private fun libraryItemFromEpub(
        sourceId: String,
        identity: String,
        file: WalkedFile,
        metadata: EpubMetadata,
        coverFile: java.io.File?,
    ): LibraryItemEntity = LibraryItemEntity(
        sourceId = sourceId,
        id = identity,
        libraryId = LocalFilesCatalog.LOCAL_ROOT_ID,
        title = metadata.title?.ifBlank { null } ?: stripExtension(file.displayName),
        author = metadata.author?.ifBlank { null } ?: "",
        coverUrl = coverFile?.toURI()?.toString(),
        readingProgress = 0f,
        ebookFormat = EBOOK_FORMAT_EPUB,
        description = metadata.description,
        seriesName = metadata.seriesName,
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
        FileClassifier.Kind.UNKNOWN -> EBOOK_FORMAT_UNSUPPORTED
    }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    companion object {
        const val EBOOK_FORMAT_EPUB: String = "epub"
        const val EBOOK_FORMAT_PDF: String = "pdf"
        const val EBOOK_FORMAT_UNSUPPORTED: String = "unsupported"
        private const val HEAD_BYTES: Int = 64 * 1024
    }
}

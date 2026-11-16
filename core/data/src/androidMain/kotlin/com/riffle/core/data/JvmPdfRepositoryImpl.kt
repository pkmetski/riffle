package com.riffle.core.data

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.common.FileStore
import com.riffle.core.domain.JvmPdfRepository
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.PdfOpenResult
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.models.LibraryItem
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File

class JvmPdfRepositoryImpl(
    fileStore: FileStore,
    catalogRegistry: CatalogRegistry,
    positionStore: ReadingPositionStore,
    localAvailabilityEvents: LocalAvailabilityEvents = NoopLocalAvailabilityEvents,
    fileSystem: FileSystem = FileSystem.SYSTEM,
) : PdfRepositoryImpl(
    fileStore = fileStore,
    catalogRegistry = catalogRegistry,
    positionStore = positionStore,
    localAvailabilityEvents = localAvailabilityEvents,
    fileSystem = fileSystem,
),
    JvmPdfRepository {

    override suspend fun openPdf(item: LibraryItem): PdfOpenResult {
        val downloadFile = File(downloadPath(item.sourceId, item.id))
        val cacheFile = File(cachePath(item.sourceId, item.id))
        val pdfFile = when {
            downloadFile.isValidPdf() -> downloadFile
            cacheFile.isValidPdf() -> cacheFile
            else -> null
        }
        if (pdfFile != null) {
            val lastPosition = positionStore.load(item.sourceId, item.id)
            return PdfOpenResult.Success(pdfFile = pdfFile, lastPosition = lastPosition)
        }
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return PdfOpenResult.NetworkError(IllegalStateException("No catalog for item"))
        return lockFor(item.sourceId, item.id).withLock {
            val dlAfterLock = downloadPath(item.sourceId, item.id)
            val cAfterLock = cachePath(item.sourceId, item.id)
            if (File(dlAfterLock).isValidPdf()) {
                val lastPosition = positionStore.load(item.sourceId, item.id)
                return@withLock PdfOpenResult.Success(File(dlAfterLock), lastPosition)
            }
            if (File(cAfterLock).isValidPdf()) {
                val lastPosition = positionStore.load(item.sourceId, item.id)
                return@withLock PdfOpenResult.Success(File(cAfterLock), lastPosition)
            }
            val destPath = cAfterLock.toPath()
            try {
                catalog.withFileStream(item.id, BookFormat.Pdf, item.ebookFileIno) { stream ->
                    fileSystem.createDirectories(destPath.parent!!)
                    fileSystem.write(destPath) {
                        val buffer = ByteArray(CHUNK_BYTES)
                        while (true) {
                            val read = stream.channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) break
                            write(buffer, 0, read)
                        }
                    }
                }
                localAvailabilityEvents.notifyChanged(item.sourceId, item.id)
                val lastPosition = positionStore.load(item.sourceId, item.id)
                PdfOpenResult.Success(pdfFile = File(cAfterLock), lastPosition = lastPosition)
            } catch (e: CancellationException) {
                runCatching { fileSystem.delete(destPath) }
                throw e
            } catch (t: Throwable) {
                runCatching { fileSystem.delete(destPath) }
                PdfOpenResult.NetworkError(t)
            }
        }
    }

    override suspend fun openPdfForMetadata(item: LibraryItem): PdfOpenResult {
        val downloadFile = File(downloadPath(item.sourceId, item.id))
        val cacheFile = File(cachePath(item.sourceId, item.id))
        val local = when {
            downloadFile.isValidPdf() -> downloadFile
            cacheFile.isValidPdf() -> cacheFile
            else -> null
        }
        if (local != null) {
            return PdfOpenResult.Success(pdfFile = local, lastPosition = null, temporary = false)
        }
        val catalog = catalogRegistry.forSourceId(item.sourceId)
            ?: return PdfOpenResult.NetworkError(IllegalStateException("No catalog for item"))
        return lockFor(item.sourceId, item.id).withLock {
            val dlAfterLock = downloadPath(item.sourceId, item.id)
            val cAfterLock = cachePath(item.sourceId, item.id)
            if (File(dlAfterLock).isValidPdf()) return@withLock PdfOpenResult.Success(File(dlAfterLock), null)
            if (File(cAfterLock).isValidPdf()) return@withLock PdfOpenResult.Success(File(cAfterLock), null)
            val temp = java.io.File.createTempFile("riffle-metadata-", ".pdf")
            try {
                catalog.withFileStream(item.id, BookFormat.Pdf, item.ebookFileIno) { stream ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(CHUNK_BYTES)
                        while (!stream.channel.isClosedForRead) {
                            val read = stream.channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) continue
                            output.write(buffer, 0, read)
                        }
                    }
                }
                PdfOpenResult.Success(pdfFile = temp, lastPosition = null, temporary = true)
            } catch (e: CancellationException) {
                temp.delete()
                throw e
            } catch (t: Throwable) {
                temp.delete()
                PdfOpenResult.NetworkError(t)
            }
        }
    }
}

private fun File.isValidPdf(): Boolean {
    if (!exists() || length() < 8) return false
    val headerOk = inputStream().use { stream ->
        val header = ByteArray(4).also { stream.read(it) }
        header.contentEquals("%PDF".toByteArray())
    }
    if (!headerOk) return false
    val tailSize = minOf(32L, length()).toInt()
    val tail = ByteArray(tailSize)
    java.io.RandomAccessFile(this, "r").use { raf ->
        raf.seek(length() - tailSize)
        raf.readFully(tail)
    }
    return String(tail).contains("%%EOF")
}

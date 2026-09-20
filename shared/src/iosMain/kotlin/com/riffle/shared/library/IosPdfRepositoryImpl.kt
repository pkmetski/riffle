package com.riffle.shared.library

import com.riffle.core.common.FileStore
import com.riffle.core.data.NS_PDF_CACHE
import com.riffle.core.data.NS_PDF_DOWNLOADS
import com.riffle.core.domain.PdfDownloadResult
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.domain.SourceRepository
import com.riffle.core.domain.TokenStorage
import com.riffle.core.models.LibraryItem
import com.riffle.core.network.withHttpChannelStream
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSMutableData
import platform.Foundation.appendBytes

/** Path scheme for locally stored PDFs, mirroring [IosEpubPaths]. */
internal object IosPdfPaths {
    fun downloadRelativePath(sourceId: String, itemId: String) = "$sourceId/$itemId.pdf"
    fun cacheRelativePath(sourceId: String, itemId: String) = "$sourceId/$itemId.pdf"
}

/**
 * iOS [PdfRepository] — the PDF twin of [IosEpubRepositoryImpl], streaming the ABS file endpoint
 * into the pdf-downloads namespace that [com.riffle.core.data.IosLibraryItemOfflineAvailabilityImpl]
 * already checks when deciding whether a PDF is available offline.
 */
internal class IosPdfRepositoryImpl(
    private val positionStore: ReadingPositionStore,
    private val fileStore: FileStore,
    private val sourceRepository: SourceRepository,
    private val tokenStorage: TokenStorage,
    private val httpClient: HttpClient,
) : PdfRepository {

    override suspend fun saveReadingPosition(sourceId: String, itemId: String, locatorJson: String) {
        positionStore.save(sourceId, itemId, locatorJson)
    }

    override fun isDownloaded(sourceId: String, itemId: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(downloadPath(sourceId, itemId))

    override fun isCached(sourceId: String, itemId: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(cachePath(sourceId, itemId))

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun downloadPdf(
        item: LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): PdfDownloadResult {
        if (isDownloaded(item.sourceId, item.id)) return PdfDownloadResult.AlreadyDownloaded

        // No getActive() fallback — see IosCbzReaderBackends (#1071 §11).
        val source = sourceRepository.getById(item.sourceId)
            ?: return PdfDownloadResult.NetworkError(IllegalStateException("Source unavailable"))
        val token = tokenStorage.getToken(source.id)
            ?: return PdfDownloadResult.NetworkError(IllegalStateException("No auth token for source ${source.id}"))
        val fileIno = item.ebookFileIno
            ?: return PdfDownloadResult.NetworkError(IllegalStateException("Item ${item.id} has no ebookFileIno"))

        val urlString = "${source.url.value.trimEnd('/')}/api/items/${item.id}/file/$fileIno"
        val destPath = downloadPath(item.sourceId, item.id)

        return runCatching {
            httpClient.withHttpChannelStream(
                url = urlString,
                headers = mapOf(HttpHeaders.Authorization to "Bearer $token"),
            ) { stream ->
                val accumulator = NSMutableData()
                var downloaded = 0L
                val buffer = ByteArray(8 * 1024)

                while (!stream.channel.isClosedForRead) {
                    val read = stream.channel.readAvailable(buffer)
                    if (read <= 0) break
                    buffer.copyOf(read).usePinned { p ->
                        accumulator.appendBytes(p.addressOf(0), read.toULong())
                    }
                    downloaded += read
                    onProgress(downloaded, stream.contentLength)
                }

                val written = NSFileManager.defaultManager.createFileAtPath(
                    path = destPath,
                    contents = accumulator,
                    attributes = null,
                )
                if (written) {
                    PdfDownloadResult.Success
                } else {
                    PdfDownloadResult.NetworkError(IllegalStateException("Failed to write file to $destPath"))
                }
            }
        }.getOrElse { PdfDownloadResult.NetworkError(it) }
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun removeDownload(sourceId: String, itemId: String) {
        NSFileManager.defaultManager.removeItemAtPath(downloadPath(sourceId, itemId), null)
        // Also remove the cache copy so isCached() stays consistent.
        NSFileManager.defaultManager.removeItemAtPath(cachePath(sourceId, itemId), null)
    }

    /** Local path of an already-stored PDF (download preferred over cache), or null when absent. */
    fun localPath(sourceId: String, itemId: String): String? {
        val download = downloadPath(sourceId, itemId)
        if (NSFileManager.defaultManager.fileExistsAtPath(download)) return download
        val cache = cachePath(sourceId, itemId)
        return if (NSFileManager.defaultManager.fileExistsAtPath(cache)) cache else null
    }

    private fun downloadPath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_PDF_DOWNLOADS, IosPdfPaths.downloadRelativePath(sourceId, itemId))

    private fun cachePath(sourceId: String, itemId: String): String =
        fileStore.resolve(NS_PDF_CACHE, IosPdfPaths.cacheRelativePath(sourceId, itemId))
}

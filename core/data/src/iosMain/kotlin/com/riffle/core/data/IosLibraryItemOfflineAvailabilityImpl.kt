package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

// Path namespaces for iOS local book storage, mirroring Android's LocalStoreImpl convention:
// files live at <Documents>/<namespace>/<sourceId>/<itemId>.<ext>.
const val NS_EPUB_DOWNLOADS = "epub-downloads"
const val NS_EPUB_CACHE = "epub-cache"
const val NS_PDF_DOWNLOADS = "pdf-downloads"
const val NS_PDF_CACHE = "pdf-cache"
const val NS_CBZ_DOWNLOADS = "cbz-downloads"
const val NS_CBZ_CACHE = "cbz-cache"
// Audiobook downloads are a directory (multiple track files) keyed by <sourceId>/<itemId>.
const val NS_AUDIOBOOK_DOWNLOADS = "audiobook-downloads"

@OptIn(ExperimentalForeignApi::class)
class IosLibraryItemOfflineAvailabilityImpl(
    private val fileStore: FileStore,
) : LibraryItemOfflineAvailability {

    override fun isAvailableOffline(item: LibraryItem): Boolean {
        val ebookAvailable = when (item.ebookFormat) {
            EbookFormat.Epub ->
                fileExists(NS_EPUB_DOWNLOADS, item.sourceId, item.id, ".epub") ||
                    fileExists(NS_EPUB_CACHE, item.sourceId, item.id, ".epub")
            EbookFormat.Pdf ->
                fileExists(NS_PDF_DOWNLOADS, item.sourceId, item.id, ".pdf") ||
                    fileExists(NS_PDF_CACHE, item.sourceId, item.id, ".pdf")
            EbookFormat.Cbz ->
                fileExists(NS_CBZ_DOWNLOADS, item.sourceId, item.id, ".cbz") ||
                    fileExists(NS_CBZ_CACHE, item.sourceId, item.id, ".cbz")
            EbookFormat.Unsupported -> false
        }
        return ebookAvailable || audiobookDownloaded(item.sourceId, item.id)
    }

    private fun fileExists(namespace: String, sourceId: String, itemId: String, extension: String): Boolean {
        val path = fileStore.resolve(namespace, "$sourceId/$itemId$extension")
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    private fun audiobookDownloaded(sourceId: String, itemId: String): Boolean {
        val path = fileStore.resolve(NS_AUDIOBOOK_DOWNLOADS, "$sourceId/$itemId")
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }
}

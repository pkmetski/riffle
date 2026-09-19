package com.riffle.shared.reader

import com.riffle.core.common.FileStore
import com.riffle.core.data.NS_EPUB_CACHE
import com.riffle.core.data.NS_EPUB_DOWNLOADS
import com.riffle.core.domain.EbookCfiTranslator
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.IosEbookCfiTranslator
import com.riffle.shared.library.IosEpubPaths
import platform.Foundation.NSFileManager

/**
 * iOS [EbookCfiTranslatorFactory]: resolves the already-downloaded-or-cached local EPUB copy for
 * (sourceId, itemId) using the same path scheme as [IosEpubDownloader]/[IosEpubRepositoryImpl],
 * and hands it to [IosEbookCfiTranslator]. Returns null when the EPUB isn't cached locally —
 * matching the interface contract: the caller (progress-sync reconcile) treats a null translator
 * as "defer this cycle", never as an error.
 */
internal class IosEbookCfiTranslatorFactory(private val fileStore: FileStore) : EbookCfiTranslatorFactory {

    override fun forItem(sourceId: String, itemId: String): EbookCfiTranslator? {
        val downloadPath = fileStore.resolve(NS_EPUB_DOWNLOADS, IosEpubPaths.downloadRelativePath(sourceId, itemId))
        if (NSFileManager.defaultManager.fileExistsAtPath(downloadPath)) {
            return IosEbookCfiTranslator(downloadPath)
        }
        val cachePath = fileStore.resolve(NS_EPUB_CACHE, IosEpubPaths.cacheRelativePath(sourceId, itemId))
        if (NSFileManager.defaultManager.fileExistsAtPath(cachePath)) {
            return IosEbookCfiTranslator(cachePath)
        }
        return null
    }
}

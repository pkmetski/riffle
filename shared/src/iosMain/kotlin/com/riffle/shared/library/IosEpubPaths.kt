package com.riffle.shared.library

// Centralises on-disk path formulas for EPUB files so that IosEpubRepositoryImpl,
// IosEpubDownloader, and IosDownloadsRepositoryImpl all agree without coupling.
internal object IosEpubPaths {
    fun downloadRelativePath(sourceId: String, itemId: String) = "$sourceId/$itemId.epub"
    fun cacheRelativePath(sourceId: String, itemId: String) = "$sourceId/$itemId.epub"
}

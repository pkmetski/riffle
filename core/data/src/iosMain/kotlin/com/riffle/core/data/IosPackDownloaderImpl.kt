package com.riffle.core.data

import com.riffle.core.dictionary.LanguageCatalogEntry
import com.riffle.core.dictionary.PackDownloaderInterface

// No-op PackDownloaderInterface for iOS. Dictionary pack downloading is not yet supported
// on iOS; always returns false so callers can handle the not-downloaded state gracefully.
internal class IosPackDownloaderImpl : PackDownloaderInterface {
    override suspend fun download(
        entry: LanguageCatalogEntry,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Boolean = false
}

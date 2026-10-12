package com.riffle.core.dictionary

/**
 * Platform-agnostic contract for downloading a dictionary pack. The Android implementation
 * fetches from the network and converts JSONL to SQLite; iOS provides a no-op until the
 * equivalent pipeline is built.
 */
interface PackDownloaderInterface {
    suspend fun download(
        entry: LanguageCatalogEntry,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean
}

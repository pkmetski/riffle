package com.riffle.app.feature.library

import com.riffle.core.domain.CbzRepository

/** Shared test double for ViewModel tests that don't exercise the CBZ path. */
internal class NoopCbzRepository(
    private val downloaded: Boolean = false,
    private val cached: Boolean = false,
) : CbzRepository {
    override fun isDownloaded(sourceId: String, itemId: String) = downloaded
    override fun isCached(sourceId: String, itemId: String) = cached
    override suspend fun openCbz(item: com.riffle.core.domain.LibraryItem) = error("unused in test")
    override suspend fun downloadCbz(
        item: com.riffle.core.domain.LibraryItem,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) = error("unused in test")
    override suspend fun removeDownload(sourceId: String, itemId: String) = error("unused in test")
    override suspend fun saveReadingPosition(itemId: String, locatorJson: String) = error("unused in test")
}

package com.riffle.shared.library

import com.riffle.core.domain.EpubDownloadResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.ReadingPositionStore
import com.riffle.core.models.LibraryItem

// iOS EpubRepository that persists reading positions via the real ReadingPositionStore.
// File I/O (downloadEpub, isCached, etc.) is handled by the native iOS layer and is not
// needed here — those methods return safe no-op defaults.
internal class IosEpubRepositoryImpl(
    private val positionStore: ReadingPositionStore,
) : EpubRepository {

    override suspend fun saveReadingPosition(sourceId: String, itemId: String, cfi: String) {
        positionStore.save(sourceId, itemId, cfi)
    }

    override suspend fun loadLastPosition(sourceId: String, itemId: String): String? =
        positionStore.load(sourceId, itemId)

    override suspend fun downloadEpub(
        item: LibraryItem,
        onProgress: (Long, Long) -> Unit,
    ): EpubDownloadResult = EpubDownloadResult.AlreadyDownloaded

    override suspend fun removeDownload(sourceId: String, itemId: String) {}

    override fun isDownloaded(sourceId: String, itemId: String): Boolean = false

    override fun isCached(sourceId: String, itemId: String): Boolean = false
}

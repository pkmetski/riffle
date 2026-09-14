package com.riffle.core.data

import com.riffle.core.common.Clock
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.LibraryMutator
import com.riffle.core.domain.SourceRepository

internal class IosLibraryMutatorImpl(
    private val libraryItemDao: LibraryItemDao,
    private val sourceRepository: SourceRepository,
    private val clock: Clock,
) : LibraryMutator {

    override suspend fun markItemOpened(itemId: String) {
        val sourceId = sourceRepository.getActive()?.id ?: return
        libraryItemDao.updateLastOpenedAt(sourceId, itemId, clock.nowMs())
    }

    override suspend fun updateReadingProgress(itemId: String, progress: Float) {
        val sourceId = sourceRepository.getActive()?.id ?: return
        libraryItemDao.updateReadingProgress(sourceId, itemId, progress)
    }

    override suspend fun updateReadingProgress(sourceId: String, itemId: String, progress: Float) {
        libraryItemDao.updateReadingProgress(sourceId, itemId, progress)
    }

    override suspend fun deleteItem(sourceId: String, itemId: String) {
        libraryItemDao.deleteById(sourceId, itemId)
    }
}

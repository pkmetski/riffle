package com.riffle.core.data.localfiles

import com.riffle.core.database.LocalFileMetadataOverrideDao
import javax.inject.Inject

class RevertTitleOverrideUseCase @Inject constructor(
    private val overrideDao: LocalFileMetadataOverrideDao,
) {
    /** Clears the title override so the catalog falls back to the scanner-extracted value. No-op if no override exists. */
    suspend operator fun invoke(sourceId: String, sourceItemId: String) {
        val existing = overrideDao.getForItem(sourceId, sourceItemId) ?: return
        overrideDao.upsert(existing.copy(title = null))
    }
}

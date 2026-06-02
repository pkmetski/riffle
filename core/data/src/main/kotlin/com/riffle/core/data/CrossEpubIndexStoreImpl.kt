package com.riffle.core.data

import com.riffle.core.database.CrossEpubIndexDao
import com.riffle.core.database.CrossEpubIndexEntity
import com.riffle.core.domain.CrossEpubIndex
import com.riffle.core.domain.CrossEpubIndexSerializer
import com.riffle.core.domain.CrossEpubIndexStore
import javax.inject.Inject

/** [CrossEpubIndexStore] backed by the `cross_epub_index` Room table. */
class CrossEpubIndexStoreImpl @Inject constructor(
    private val dao: CrossEpubIndexDao,
) : CrossEpubIndexStore {

    override suspend fun exists(absChecksum: String, storytellerChecksum: String): Boolean =
        dao.find(absChecksum, storytellerChecksum) != null

    override suspend fun load(absChecksum: String, storytellerChecksum: String): CrossEpubIndex? =
        dao.find(absChecksum, storytellerChecksum)?.let { CrossEpubIndexSerializer.decode(it.perChapterMapsBlob) }

    override suspend fun put(absChecksum: String, storytellerChecksum: String, blob: String, builtAt: Long) {
        dao.upsert(
            CrossEpubIndexEntity(
                absEpubChecksum = absChecksum,
                storytellerEpubChecksum = storytellerChecksum,
                perChapterMapsBlob = blob,
                builtAt = builtAt,
            )
        )
    }
}

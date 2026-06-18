package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.DeviceIdStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class AnnotationStoreImpl(
    private val dao: AnnotationDao,
    private val deviceIdStore: DeviceIdStore,
    private val clock: () -> Long,
    private val idGenerator: () -> String,
) : AnnotationStore {

    @Inject
    constructor(dao: AnnotationDao, deviceIdStore: DeviceIdStore) : this(
        dao = dao,
        deviceIdStore = deviceIdStore,
        clock = { System.currentTimeMillis() },
        idGenerator = { UUID.randomUUID().toString() },
    )

    override fun observeHighlights(serverId: String, itemId: String): Flow<List<Annotation>> =
        dao.observeForItem(serverId, itemId).map { rows ->
            rows.filter { it.type == AnnotationEntity.TYPE_HIGHLIGHT }.map { it.toDomain() }
        }

    override fun observeBookmarks(serverId: String, itemId: String): Flow<List<Annotation>> =
        dao.observeForItem(serverId, itemId).map { rows ->
            rows.filter { it.type == AnnotationEntity.TYPE_BOOKMARK }.map { it.toDomain() }
        }

    override suspend fun createHighlight(
        serverId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        textBefore: String,
        textAfter: String,
        color: String,
    ): Annotation {
        val deviceId = deviceIdStore.getOrCreate()
        val now = clock()
        val entity = AnnotationEntity(
            id = idGenerator(),
            serverId = serverId,
            itemId = itemId,
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = cfi,
            color = color,
            note = null,
            textSnippet = textSnippet,
            textBefore = textBefore,
            textAfter = textAfter,
            chapterHref = chapterHref,
            createdAt = now,
            updatedAt = now,
            originDeviceId = deviceId,
            lastModifiedByDeviceId = deviceId,
            deleted = false,
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun createBookmark(
        serverId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
    ): Annotation {
        val deviceId = deviceIdStore.getOrCreate()
        val now = clock()
        val entity = AnnotationEntity(
            id = idGenerator(),
            serverId = serverId,
            itemId = itemId,
            type = AnnotationEntity.TYPE_BOOKMARK,
            cfi = cfi,
            color = "",
            note = null,
            textSnippet = textSnippet,
            chapterHref = chapterHref,
            createdAt = now,
            updatedAt = now,
            originDeviceId = deviceId,
            lastModifiedByDeviceId = deviceId,
            deleted = false,
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun delete(id: String) {
        dao.tombstone(id, updatedAt = clock(), deviceId = deviceIdStore.getOrCreate())
    }

    override suspend fun recolor(id: String, color: String) {
        dao.recolor(id, color = color, updatedAt = clock(), deviceId = deviceIdStore.getOrCreate())
    }
}

private fun AnnotationEntity.toDomain() = Annotation(
    id = id,
    serverId = serverId,
    itemId = itemId,
    type = type,
    cfi = cfi,
    color = color,
    note = note,
    textSnippet = textSnippet,
    textBefore = textBefore,
    textAfter = textAfter,
    chapterHref = chapterHref,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

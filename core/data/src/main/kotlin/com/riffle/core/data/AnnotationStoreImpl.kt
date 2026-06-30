package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.Annotation
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.Clock
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
    constructor(dao: AnnotationDao, deviceIdStore: DeviceIdStore, clock: Clock) : this(
        dao = dao,
        deviceIdStore = deviceIdStore,
        clock = clock::nowMs,
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

    override fun observeAnnotations(serverId: String, itemId: String): Flow<List<Annotation>> =
        dao.observeAnnotationsByPosition(serverId, itemId).map { rows -> rows.map { it.toDomain() } }

    override fun observeAnnotationsForServer(serverId: String): Flow<List<Annotation>> =
        dao.observeForServer(serverId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun createHighlight(
        serverId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        textBefore: String,
        textAfter: String,
        color: String,
        spineIndex: Int,
        progression: Double,
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
            spineIndex = spineIndex,
            progression = progression,
            bookmarkTitle = "",
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
        spineIndex: Int,
        progression: Double,
        bookmarkTitle: String,
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
            spineIndex = spineIndex,
            progression = progression,
            bookmarkTitle = bookmarkTitle,
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

    override suspend fun updateNote(id: String, note: String?) {
        dao.updateNote(id, note = note, updatedAt = clock(), deviceId = deviceIdStore.getOrCreate())
    }

    override suspend fun renameBookmark(id: String, title: String) {
        dao.renameBookmark(id, title = title, updatedAt = clock(), deviceId = deviceIdStore.getOrCreate())
    }

    override suspend fun findByItemAndCfi(serverId: String, itemId: String, cfi: String): Annotation? =
        dao.getByItemAndCfi(serverId, itemId, cfi)?.toDomain()
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
    spineIndex = spineIndex,
    progression = progression,
    bookmarkTitle = bookmarkTitle,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

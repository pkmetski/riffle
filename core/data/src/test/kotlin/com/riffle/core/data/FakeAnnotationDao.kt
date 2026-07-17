package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.database.BookHighlightSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Minimal [AnnotationDao] fake for repository-level tests that only care about
 * [observeBooksWithHighlights]. Every other member throws so a test that accidentally exercises
 * unrelated behaviour fails loudly instead of silently returning empty data.
 */
internal class FakeAnnotationDao : AnnotationDao {

    private val summariesByServer = mutableMapOf<String, MutableStateFlow<List<BookHighlightSummary>>>()

    /** Seeds [observeBooksWithHighlights] for a given server. */
    fun emitBooksWithHighlights(sourceId: String, summaries: List<BookHighlightSummary>) {
        summariesByServer.getOrPut(sourceId) { MutableStateFlow(emptyList()) }.value = summaries
    }

    override fun observeBooksWithHighlights(sourceId: String): Flow<List<BookHighlightSummary>> =
        summariesByServer.getOrPut(sourceId) { MutableStateFlow(emptyList()) }

    override fun observeForItem(sourceId: String, itemId: String): Flow<List<AnnotationEntity>> =
        error("not used by this fake")

    override fun observeForSource(sourceId: String): Flow<List<AnnotationEntity>> =
        error("not used by this fake")

    override suspend fun getForItem(sourceId: String, itemId: String): List<AnnotationEntity> =
        error("not used by this fake")

    override suspend fun getAllForItemIncludingDeleted(sourceId: String, itemId: String): List<AnnotationEntity> =
        error("not used by this fake")

    override suspend fun getById(id: String): AnnotationEntity? = error("not used by this fake")

    override suspend fun getByItemAndCfi(sourceId: String, itemId: String, cfi: String): AnnotationEntity? =
        error("not used by this fake")

    override suspend fun findImageForFigure(
        sourceId: String,
        itemId: String,
        chapterHref: String,
        imageHref: String?,
        imageSvg: String?,
    ): AnnotationEntity? = error("not used by this fake")

    override suspend fun upsert(entity: AnnotationEntity) = error("not used by this fake")

    override suspend fun upsertAll(annotations: List<AnnotationEntity>) = error("not used by this fake")

    override suspend fun tombstone(id: String, updatedAt: Long, deviceId: String) = error("not used by this fake")

    override suspend fun recolor(id: String, color: String, updatedAt: Long, deviceId: String) =
        error("not used by this fake")

    override suspend fun updateNote(id: String, note: String?, updatedAt: Long, deviceId: String) =
        error("not used by this fake")

    override fun observeAnnotationsByPosition(sourceId: String, itemId: String): Flow<List<AnnotationEntity>> =
        error("not used by this fake")

    override suspend fun renameBookmark(id: String, title: String, updatedAt: Long, deviceId: String) =
        error("not used by this fake")

    override fun observePendingCountForBook(sourceId: String, itemId: String): Flow<Int> =
        error("not used by this fake")

    override fun observePendingBookCountAcrossAll(): Flow<Int> = error("not used by this fake")

    override suspend fun dirtySourceItems(): List<AnnotationDao.DirtySourceItem> = error("not used by this fake")

    override suspend fun markSynced(ids: List<String>, syncedAt: Long) = error("not used by this fake")

    override suspend fun purgeAgedTombstones(sourceId: String, itemId: String, cutoff: Long): Int =
        error("not used by this fake")

    override suspend fun backfillNullOriginFontFamily(
        sourceId: String,
        itemId: String,
        fontFamily: String,
        updatedAt: Long,
        deviceId: String,
    ): Int = error("not used by this fake")

    override suspend fun healSentinelOriginFontFamily(
        sourceId: String,
        itemId: String,
        sentinel: String,
        fontFamily: String,
        updatedAt: Long,
        deviceId: String,
    ): Int = 0

    override suspend fun updateEmphasisStyles(
        id: String,
        emphasisStyles: String,
        updatedAt: Long,
        deviceId: String,
    ) = error("not used by this fake")
}

package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {

    /** Live, non-deleted annotations for an ABS Library Item, oldest first. */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND deleted = 0 " +
            "ORDER BY createdAt ASC"
    )
    fun observeForItem(serverId: String, itemId: String): Flow<List<AnnotationEntity>>

    /** One-shot read of non-deleted annotations for an ABS Library Item, oldest first. */
    @Query(
        "SELECT * FROM annotations WHERE serverId = :serverId AND itemId = :itemId AND deleted = 0 " +
            "ORDER BY createdAt ASC"
    )
    suspend fun getForItem(serverId: String, itemId: String): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AnnotationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AnnotationEntity)

    /** Tombstone an annotation so the delete can later propagate to other devices. */
    @Query("UPDATE annotations SET deleted = 1, updatedAt = :updatedAt, lastModifiedByDeviceId = :deviceId WHERE id = :id")
    suspend fun tombstone(id: String, updatedAt: Long, deviceId: String)
}

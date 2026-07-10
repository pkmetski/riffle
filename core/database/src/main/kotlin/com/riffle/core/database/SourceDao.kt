package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {

    @Query("SELECT * FROM sources ORDER BY isActive DESC, serverType ASC, username ASC, url ASC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): SourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity)

    @Query("UPDATE sources SET isActive = 0")
    suspend fun clearActiveFlag()

    @Query("UPDATE sources SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    @Transaction
    suspend fun setActiveAtomic(id: String) {
        clearActiveFlag()
        setActive(id)
    }

    @Transaction
    suspend fun upsertAsFirstIfNoActive(source: SourceEntity): SourceEntity {
        val hasActive = getActive() != null
        val toInsert = source.copy(isActive = !hasActive)
        upsert(toInsert)
        return toInsert
    }

    @Query("SELECT * FROM sources WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SourceEntity?

    /**
     * First source row whose [SourceEntity.type] matches [type] (there is at most one LocalFiles
     * source per device; this returns it or `null` when none has been installed yet).
     */
    @Query("SELECT * FROM sources WHERE type = :type LIMIT 1")
    suspend fun getByType(type: String): SourceEntity?

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE sources SET absUserId = :absUserId WHERE id = :id")
    suspend fun setAbsUserId(id: String, absUserId: String)
}

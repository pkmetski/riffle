package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {

    @Query("SELECT * FROM servers ORDER BY isActive DESC, serverType ASC, username ASC, url ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: ServerEntity)

    @Query("UPDATE servers SET isActive = 0")
    suspend fun clearActiveFlag()

    @Query("UPDATE servers SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: String)

    @Transaction
    suspend fun setActiveAtomic(id: String) {
        clearActiveFlag()
        setActive(id)
    }

    @Transaction
    suspend fun upsertAsFirstIfNoActive(server: ServerEntity): ServerEntity {
        val hasActive = getActive() != null
        val toInsert = server.copy(isActive = !hasActive)
        upsert(toInsert)
        return toInsert
    }

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServerEntity?

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: String)
}

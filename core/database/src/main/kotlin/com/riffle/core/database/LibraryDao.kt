package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Query("SELECT * FROM libraries WHERE serverId = :serverId ORDER BY name ASC")
    fun observeByServerId(serverId: String): Flow<List<LibraryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(libraries: List<LibraryEntity>)

    @Query("DELETE FROM libraries WHERE serverId = :serverId")
    suspend fun deleteByServerId(serverId: String)
}

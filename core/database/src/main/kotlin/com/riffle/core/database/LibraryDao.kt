package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Query("SELECT * FROM libraries WHERE serverId = :serverId ORDER BY name ASC")
    fun observeByServerId(serverId: String): Flow<List<LibraryEntity>>

    @Query("SELECT id FROM libraries WHERE serverId = :serverId")
    suspend fun libraryIdsForServer(serverId: String): List<String>

    @Query("SELECT * FROM libraries WHERE serverId = :serverId AND id = :libraryId LIMIT 1")
    suspend fun getById(serverId: String, libraryId: String): LibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(libraries: List<LibraryEntity>)

    @Query("DELETE FROM libraries WHERE serverId = :serverId")
    suspend fun deleteByServerId(serverId: String)

    @Transaction
    suspend fun replaceAllForServer(serverId: String, libraries: List<LibraryEntity>) {
        deleteByServerId(serverId)
        upsertAll(libraries)
    }

    @Query("UPDATE libraries SET isUnsupported = :isUnsupported WHERE serverId = :serverId AND id = :libraryId")
    suspend fun setUnsupported(serverId: String, libraryId: String, isUnsupported: Boolean)
}

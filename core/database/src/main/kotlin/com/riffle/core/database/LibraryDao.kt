package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    @Query("SELECT * FROM libraries WHERE sourceId = :sourceId ORDER BY name ASC")
    fun observeByServerId(sourceId: String): Flow<List<LibraryEntity>>

    @Query("SELECT id FROM libraries WHERE sourceId = :sourceId")
    suspend fun libraryIdsForServer(sourceId: String): List<String>

    @Query("SELECT * FROM libraries WHERE sourceId = :sourceId AND id = :libraryId LIMIT 1")
    suspend fun getById(sourceId: String, libraryId: String): LibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(libraries: List<LibraryEntity>)

    @Query("DELETE FROM libraries WHERE sourceId = :sourceId")
    suspend fun deleteByServerId(sourceId: String)

    @Transaction
    suspend fun replaceAllForServer(sourceId: String, libraries: List<LibraryEntity>) {
        deleteByServerId(sourceId)
        upsertAll(libraries)
    }

    @Query("UPDATE libraries SET isUnsupported = :isUnsupported WHERE sourceId = :sourceId AND id = :libraryId")
    suspend fun setUnsupported(sourceId: String, libraryId: String, isUnsupported: Boolean)
}

package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {

    @Query("SELECT * FROM collections WHERE libraryId = :libraryId ORDER BY name ASC")
    fun observeByLibraryId(libraryId: String): Flow<List<CollectionEntity>>

    @Query("""
        SELECT li.* FROM library_items li
        INNER JOIN collection_items ci ON li.id = ci.itemId
        WHERE ci.collectionId = :collectionId
        ORDER BY li.title ASC
    """)
    fun observeItemsByCollectionId(collectionId: String): Flow<List<LibraryItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(collections: List<CollectionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllItems(items: List<CollectionItemEntity>)

    @Query("DELETE FROM collections WHERE libraryId = :libraryId")
    suspend fun deleteByLibraryId(libraryId: String)

    @Query("DELETE FROM collection_items WHERE collectionId IN (SELECT id FROM collections WHERE libraryId = :libraryId)")
    suspend fun deleteItemsByLibraryId(libraryId: String)
}

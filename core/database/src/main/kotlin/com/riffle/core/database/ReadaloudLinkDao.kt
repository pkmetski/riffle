package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadaloudLinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReadaloudLinkEntity)

    @Query(
        "SELECT * FROM readaloud_links " +
            "WHERE storytellerServerId = :storytellerServerId AND storytellerBookId = :storytellerBookId LIMIT 1"
    )
    suspend fun findByStorytellerBook(storytellerServerId: String, storytellerBookId: String): ReadaloudLinkEntity?

    @Query("SELECT * FROM readaloud_links WHERE absServerId = :absServerId AND absLibraryItemId = :absLibraryItemId LIMIT 1")
    suspend fun findByAbsItem(absServerId: String, absLibraryItemId: String): ReadaloudLinkEntity?

    @Query("SELECT * FROM readaloud_links")
    fun observeAll(): Flow<List<ReadaloudLinkEntity>>

    /** Set of ABS Library Item ids that currently have any link — drives the ABS-side grid badge. */
    @Query("SELECT absLibraryItemId FROM readaloud_links")
    fun observeLinkedAbsItemIds(): Flow<List<String>>

    /** Set of Storyteller book ids with a link — drives the Readaloud-side footer state. */
    @Query("SELECT storytellerBookId FROM readaloud_links")
    fun observeLinkedStorytellerBookIds(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM readaloud_links WHERE storytellerServerId = :serverId OR absServerId = :serverId")
    suspend fun countForServer(serverId: String): Int

    @Query(
        "DELETE FROM readaloud_links " +
            "WHERE storytellerServerId = :storytellerServerId AND storytellerBookId = :storytellerBookId"
    )
    suspend fun deleteByStorytellerBook(storytellerServerId: String, storytellerBookId: String)
}

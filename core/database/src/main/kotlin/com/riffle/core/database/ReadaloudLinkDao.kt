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

    /** PK lookup — at most one row per (absSourceId, absLibraryItemId). */
    @Query("SELECT * FROM readaloud_links WHERE absSourceId = :absSourceId AND absLibraryItemId = :absLibraryItemId LIMIT 1")
    suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLinkEntity?

    /** A Storyteller readaloud can be linked from multiple ABS items (ebook + audiobook). */
    @Query(
        "SELECT * FROM readaloud_links " +
            "WHERE storytellerSourceId = :storytellerSourceId AND storytellerBookId = :storytellerBookId"
    )
    suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLinkEntity>

    @Query("SELECT * FROM readaloud_links")
    fun observeAll(): Flow<List<ReadaloudLinkEntity>>

    /** One-shot snapshot, used by the reconciler to enumerate rows for stale-sweep. */
    @Query("SELECT * FROM readaloud_links")
    suspend fun allRows(): List<ReadaloudLinkEntity>

    /** Set of ABS Library Item ids with any link — drives the ABS-side grid badge. */
    @Query("SELECT absLibraryItemId FROM readaloud_links")
    fun observeLinkedAbsItemIds(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM readaloud_links WHERE storytellerSourceId = :sourceId OR absSourceId = :sourceId")
    suspend fun countForServer(sourceId: String): Int

    /** Unlink a specific ABS item from its readaloud. */
    @Query("DELETE FROM readaloud_links WHERE absSourceId = :absSourceId AND absLibraryItemId = :absLibraryItemId")
    suspend fun deleteByAbsItem(absSourceId: String, absLibraryItemId: String)

    /** Unlink every ABS item that was paired with this readaloud. */
    @Query(
        "DELETE FROM readaloud_links " +
            "WHERE storytellerSourceId = :storytellerSourceId AND storytellerBookId = :storytellerBookId"
    )
    suspend fun deleteByStorytellerBook(storytellerSourceId: String, storytellerBookId: String)

    /** Persist the streaming identity verdict for an ABS item (ADR 0028). */
    @Query("UPDATE readaloud_links SET identityResult = :result WHERE absSourceId = :absSourceId AND absLibraryItemId = :absLibraryItemId")
    suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: String)
}

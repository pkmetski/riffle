package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadaloudCandidateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReadaloudCandidateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ReadaloudCandidateEntity>)

    @Query("SELECT * FROM readaloud_candidates")
    suspend fun allRows(): List<ReadaloudCandidateEntity>

    /** Wipe the whole table — the reconciler regenerates it from the current full pass. */
    @Query("DELETE FROM readaloud_candidates")
    suspend fun clearAll()

    @Query("SELECT * FROM readaloud_candidates")
    fun observeAll(): Flow<List<ReadaloudCandidateEntity>>

    /** All Pending-Review candidates whose readaloud lives on the given Storyteller source. */
    @Query("SELECT * FROM readaloud_candidates WHERE storytellerSourceId = :storytellerSourceId")
    fun observeForStorytellerServer(storytellerSourceId: String): Flow<List<ReadaloudCandidateEntity>>

    @Query(
        "DELETE FROM readaloud_candidates " +
            "WHERE storytellerSourceId = :storytellerSourceId AND storytellerBookId = :storytellerBookId"
    )
    suspend fun deleteByStorytellerBook(storytellerSourceId: String, storytellerBookId: String)

    @Query(
        "DELETE FROM readaloud_candidates " +
            "WHERE storytellerSourceId = :storytellerSourceId AND storytellerBookId = :storytellerBookId " +
            "AND absSourceId = :absSourceId AND absLibraryItemId = :absLibraryItemId"
    )
    suspend fun deleteCandidate(
        storytellerSourceId: String,
        storytellerBookId: String,
        absSourceId: String,
        absLibraryItemId: String,
    )
}

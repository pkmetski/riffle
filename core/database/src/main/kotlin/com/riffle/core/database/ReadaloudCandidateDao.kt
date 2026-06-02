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

    /** All Pending-Review candidates whose readaloud lives on the given Storyteller server. */
    @Query("SELECT * FROM readaloud_candidates WHERE storytellerServerId = :storytellerServerId")
    fun observeForStorytellerServer(storytellerServerId: String): Flow<List<ReadaloudCandidateEntity>>

    @Query(
        "SELECT * FROM readaloud_candidates " +
            "WHERE storytellerServerId = :storytellerServerId AND storytellerBookId = :storytellerBookId"
    )
    suspend fun findByStorytellerBook(
        storytellerServerId: String,
        storytellerBookId: String,
    ): List<ReadaloudCandidateEntity>

    @Query(
        "DELETE FROM readaloud_candidates " +
            "WHERE storytellerServerId = :storytellerServerId AND storytellerBookId = :storytellerBookId"
    )
    suspend fun deleteByStorytellerBook(storytellerServerId: String, storytellerBookId: String)

    @Query(
        "DELETE FROM readaloud_candidates " +
            "WHERE storytellerServerId = :storytellerServerId AND storytellerBookId = :storytellerBookId " +
            "AND absServerId = :absServerId AND absLibraryItemId = :absLibraryItemId"
    )
    suspend fun deleteCandidate(
        storytellerServerId: String,
        storytellerBookId: String,
        absServerId: String,
        absLibraryItemId: String,
    )
}

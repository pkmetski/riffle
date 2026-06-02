package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadaloudDismissalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReadaloudDismissalEntity)

    @Query("SELECT * FROM readaloud_dismissals")
    suspend fun allRows(): List<ReadaloudDismissalEntity>

    @Query("SELECT * FROM readaloud_dismissals")
    fun observeAll(): Flow<List<ReadaloudDismissalEntity>>

    @Query(
        "SELECT * FROM readaloud_dismissals " +
            "WHERE storytellerServerId = :storytellerServerId AND storytellerBookId = :storytellerBookId"
    )
    suspend fun findByStorytellerBook(
        storytellerServerId: String,
        storytellerBookId: String,
    ): List<ReadaloudDismissalEntity>

    /** True when the user chose "No match — don't ask again" for this readaloud. */
    @Query(
        "SELECT COUNT(*) > 0 FROM readaloud_dismissals " +
            "WHERE storytellerServerId = :storytellerServerId AND storytellerBookId = :storytellerBookId " +
            "AND scope = '" + "BOOK" + "'"
    )
    suspend fun isBookDismissed(storytellerServerId: String, storytellerBookId: String): Boolean

    /** Undo a per-book "don't ask again" so the matcher can re-evaluate it. */
    @Query(
        "DELETE FROM readaloud_dismissals " +
            "WHERE storytellerServerId = :storytellerServerId AND storytellerBookId = :storytellerBookId " +
            "AND scope = '" + "BOOK" + "'"
    )
    suspend fun clearBookDismissal(storytellerServerId: String, storytellerBookId: String)
}

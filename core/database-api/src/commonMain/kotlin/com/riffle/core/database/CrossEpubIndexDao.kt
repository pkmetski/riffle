package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CrossEpubIndexDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CrossEpubIndexEntity)

    /** Hit only when both checksums match what the index was built from. */
    @Query(
        "SELECT * FROM cross_epub_index " +
            "WHERE absEpubChecksum = :absEpubChecksum AND storytellerEpubChecksum = :storytellerEpubChecksum " +
            "LIMIT 1"
    )
    suspend fun find(absEpubChecksum: String, storytellerEpubChecksum: String): CrossEpubIndexEntity?

    @Query("DELETE FROM cross_epub_index")
    suspend fun clear()
}

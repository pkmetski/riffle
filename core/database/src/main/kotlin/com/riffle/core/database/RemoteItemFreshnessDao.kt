package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RemoteItemFreshnessDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RemoteItemFreshnessEntity)

    @Query(
        "SELECT lastFetchedAt FROM remote_item_freshness " +
            "WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId"
    )
    suspend fun lastFetchedAt(sourceId: String, sourceItemId: String): Long?

    @Query(
        "DELETE FROM remote_item_freshness " +
            "WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId"
    )
    suspend fun clear(sourceId: String, sourceItemId: String)
}

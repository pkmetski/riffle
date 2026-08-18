package com.riffle.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

// Comic formatting stays per-device (never synced). sourceId FK-cascades so a removed Source's
// rows are cleared automatically.
@Entity(
    tableName = "book_comic_formatting_preferences",
    primaryKeys = ["source_id", "item_id"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("source_id")],
)
data class BookComicFormattingPreferencesEntity(
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "panel_view_on") val panelViewOn: Boolean?,
    @ColumnInfo(name = "panel_overflow") val panelOverflow: String?,
    @ColumnInfo(name = "panel_animation_speed_ms") val panelAnimationSpeedMs: Int?,
)

@Dao
interface BookComicFormattingPreferencesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BookComicFormattingPreferencesEntity)

    @Query(
        "SELECT * FROM book_comic_formatting_preferences " +
            "WHERE source_id = :sourceId AND item_id = :itemId LIMIT 1"
    )
    suspend fun getByItemId(sourceId: String, itemId: String): BookComicFormattingPreferencesEntity?

    @Query(
        "DELETE FROM book_comic_formatting_preferences " +
            "WHERE source_id = :sourceId AND item_id = :itemId"
    )
    suspend fun deleteByItemId(sourceId: String, itemId: String)
}

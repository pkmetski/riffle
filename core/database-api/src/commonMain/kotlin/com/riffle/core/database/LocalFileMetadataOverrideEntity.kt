package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * User-supplied metadata overrides for a local-file book. One row per book; all fields
 * nullable — null means "use whatever the scanner extracted". Once set, a field is never
 * overwritten by a re-scan (scanner only writes to [library_items], not this table).
 */
@Entity(
    tableName = "local_file_metadata_overrides",
    primaryKeys = ["sourceId", "sourceItemId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceId")],
)
data class LocalFileMetadataOverrideEntity(
    val sourceId: String,
    val sourceItemId: String,
    val title: String?,
    val author: String?,
    val seriesName: String?,
    val seriesIndex: Double?,
    val coverUrl: String? = null,
)

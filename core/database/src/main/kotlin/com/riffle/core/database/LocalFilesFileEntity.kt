package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One ingested local book file. Composite PK (sourceId, sourceItemId) where sourceItemId is a
 * content-based identity hash (see IdentityHasher), so the same file present in multiple folders
 * resolves to a single row. `lastSeenAtEpochMs` is bumped every scan; stale rows are hard-deleted.
 */
@Entity(
    tableName = "local_files_files",
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
data class LocalFilesFileEntity(
    val sourceId: String,
    val sourceItemId: String,
    val folderTreeUri: String,
    val originalUri: String,
    val copiedPath: String,
    val coverPath: String?,
    val format: String,
    val sizeBytes: Long,
    val mtimeEpochMs: Long,
    val lastSeenAtEpochMs: Long,
)

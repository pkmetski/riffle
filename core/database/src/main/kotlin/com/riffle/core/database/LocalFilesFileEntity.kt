package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One ingested local book file, identified by its content-based hash [sourceItemId] (see
 * IdentityHasher). Because identity is content-based, a file present in multiple monitored folders
 * collapses to a single row; folder membership is stored on [local_files_file_folders].
 * `lastSeenAtEpochMs` is the aggregate — the max across all folder memberships — and is refreshed
 * every scan pass. Rows with no surviving folder memberships are hard-deleted after a clean sweep.
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
    val originalUri: String,
    val copiedPath: String,
    val coverPath: String?,
    val format: String,
    val sizeBytes: Long,
    val mtimeEpochMs: Long,
    val lastSeenAtEpochMs: Long,
)

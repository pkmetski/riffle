package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Junction row recording that a [LocalFilesFileEntity] is present in a given
 * [LocalFilesFolderEntity]. Since a file's identity is content-based (`sourceItemId`), the same
 * bytes can live in multiple monitored folders — each folder membership gets its own row here,
 * and the file appears under every monitored folder's library. `lastSeenAtEpochMs` is stamped per
 * membership so the scanner sweep runs at membership granularity: a file removed from folder A
 * but still in folder B keeps the A-side row gone and B-side row alive.
 */
@Entity(
    tableName = "local_files_file_folders",
    primaryKeys = ["sourceId", "sourceItemId", "folderTreeUri"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sourceId"),
        Index("sourceId", "folderTreeUri"),
    ],
)
data class LocalFilesFileFolderEntity(
    val sourceId: String,
    val sourceItemId: String,
    val folderTreeUri: String,
    val lastSeenAtEpochMs: Long,
)

package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One user-picked local folder configured under a LocalFiles Source. `treeUri` is the SAF tree URI
 * the user granted persistable read permission on; scans re-walk it every time.
 */
@Entity(
    tableName = "local_files_folders",
    primaryKeys = ["sourceId", "treeUri"],
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
data class LocalFilesFolderEntity(
    val sourceId: String,
    val treeUri: String,
    val displayName: String,
    val addedAtEpochMs: Long,
)

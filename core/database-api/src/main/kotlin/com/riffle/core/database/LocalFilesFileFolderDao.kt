package com.riffle.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalFilesFileFolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalFilesFileFolderEntity)

    @Query(
        "SELECT * FROM local_files_file_folders " +
            "WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId",
    )
    suspend fun forFile(sourceId: String, sourceItemId: String): List<LocalFilesFileFolderEntity>

    /** All memberships in a given monitored folder. Used by the catalog to list a folder library. */
    @Query(
        "SELECT * FROM local_files_file_folders " +
            "WHERE sourceId = :sourceId AND folderTreeUri = :folderTreeUri",
    )
    suspend fun forFolder(sourceId: String, folderTreeUri: String): List<LocalFilesFileFolderEntity>

    /** Every file that has at least one membership in [folderTreeUri]. */
    @Query(
        "SELECT sourceItemId FROM local_files_file_folders " +
            "WHERE sourceId = :sourceId AND folderTreeUri = :folderTreeUri",
    )
    suspend fun itemIdsInFolder(sourceId: String, folderTreeUri: String): List<String>

    /** Membership rows the current scan pass didn't touch. */
    @Query(
        "SELECT * FROM local_files_file_folders " +
            "WHERE sourceId = :sourceId AND lastSeenAtEpochMs < :scanStart",
    )
    suspend fun stale(sourceId: String, scanStart: Long): List<LocalFilesFileFolderEntity>

    @Query(
        "DELETE FROM local_files_file_folders " +
            "WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId " +
            "AND folderTreeUri = :folderTreeUri",
    )
    suspend fun delete(sourceId: String, sourceItemId: String, folderTreeUri: String)

    /** All memberships for a folder — used when the user removes the folder. */
    @Query(
        "DELETE FROM local_files_file_folders " +
            "WHERE sourceId = :sourceId AND folderTreeUri = :folderTreeUri",
    )
    suspend fun deleteFolder(sourceId: String, folderTreeUri: String)

    /** All memberships for a file — used when the file row is being hard-deleted. */
    @Query(
        "DELETE FROM local_files_file_folders " +
            "WHERE sourceId = :sourceId AND sourceItemId = :sourceItemId",
    )
    suspend fun deleteFile(sourceId: String, sourceItemId: String)

    /** Files that no longer have any folder membership after the sweep. */
    @Query(
        "SELECT f.* FROM local_files_files f " +
            "WHERE f.sourceId = :sourceId " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM local_files_file_folders m " +
            "  WHERE m.sourceId = f.sourceId AND m.sourceItemId = f.sourceItemId" +
            ")",
    )
    suspend fun orphanedFiles(sourceId: String): List<LocalFilesFileEntity>
}

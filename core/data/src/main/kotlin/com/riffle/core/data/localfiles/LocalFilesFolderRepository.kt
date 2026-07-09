package com.riffle.core.data.localfiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.domain.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds/removes configured LocalFiles folders. On add, takes persistable read permission on the
 * tree URI so the folder survives process death; on remove, releases the grant.
 */
@Singleton
class LocalFilesFolderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderDao: LocalFilesFolderDao,
    private val clock: Clock,
) {

    suspend fun addFolder(sourceId: String, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val displayName = DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: treeUri.lastPathSegment
            ?: treeUri.toString()
        folderDao.upsert(
            LocalFilesFolderEntity(
                sourceId = sourceId,
                treeUri = treeUri.toString(),
                displayName = displayName,
                addedAtEpochMs = clock.nowMs(),
            ),
        )
    }

    suspend fun removeFolder(sourceId: String, treeUri: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Grant may already be gone (e.g. user cleared app data). Fall through to db delete.
        }
        folderDao.delete(sourceId, treeUri)
    }

    suspend fun folders(sourceId: String): List<LocalFilesFolderEntity> = folderDao.forSource(sourceId)
}

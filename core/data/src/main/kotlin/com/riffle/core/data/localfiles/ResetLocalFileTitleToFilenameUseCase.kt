package com.riffle.core.data.localfiles

import com.riffle.core.database.LocalFileMetadataOverrideDao
import com.riffle.core.database.LocalFileMetadataOverrideEntity
import com.riffle.core.database.LocalFilesFileDao
import java.net.URLDecoder
import javax.inject.Inject

class ResetLocalFileTitleToFilenameUseCase @Inject constructor(
    private val fileDao: LocalFilesFileDao,
    private val overrideDao: LocalFileMetadataOverrideDao,
) {
    suspend operator fun invoke(sourceId: String, sourceItemId: String) {
        val file = fileDao.findById(sourceId, sourceItemId) ?: return
        val rawName = file.displayName.ifBlank { filenameFromUri(file.originalUri) }
        val title = stripExtension(rawName).ifBlank { return }
        val existing = overrideDao.getForItem(sourceId, sourceItemId)
        overrideDao.upsert(
            existing?.copy(title = title) ?: LocalFileMetadataOverrideEntity(
                sourceId = sourceId,
                sourceItemId = sourceItemId,
                title = title,
                author = null,
                seriesName = null,
                seriesIndex = null,
            ),
        )
    }

    private fun filenameFromUri(uri: String): String = try {
        val decoded = URLDecoder.decode(uri, "UTF-8")
        // ExternalStorageProvider URIs encode the path as "primary:Dir/File.epub"; extract
        // the leaf after the last slash or colon.
        decoded.substringAfterLast('/').substringAfterLast(':')
    } catch (_: Exception) {
        ""
    }

    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}

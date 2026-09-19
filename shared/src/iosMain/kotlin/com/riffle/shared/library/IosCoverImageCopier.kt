package com.riffle.shared.library

import com.riffle.core.common.FileStore
import com.riffle.feature.library.CoverImageCopier
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL

internal const val NS_LOCAL_COVERS = "local-covers"

/**
 * iOS [CoverImageCopier] — copies a user-picked cover image into app storage and returns a stable
 * `file://` URL for it, so the pick survives the security-scoped URL the picker hands over.
 *
 * Mirrors Android's `CopyCoverImageUseCase` (ContentResolver stream → `filesDir/local_covers/
 * <sourceId>_<itemId>.jpg`), including the same file naming, using NSFileManager and a
 * security-scoped read instead.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosCoverImageCopier(private val fileStore: FileStore) : CoverImageCopier {

    override suspend fun invoke(sourceId: String, sourceItemId: String, contentUriString: String): String? {
        val sourceUrl = NSURL.URLWithString(contentUriString)
            ?: NSURL.fileURLWithPath(contentUriString)

        // Picker URLs are security-scoped: reads fail unless access is explicitly started.
        val scoped = sourceUrl.startAccessingSecurityScopedResource()
        val data: NSData? = try {
            NSData.dataWithContentsOfURL(sourceUrl)
        } finally {
            if (scoped) sourceUrl.stopAccessingSecurityScopedResource()
        }
        if (data == null || data.length == 0uL) return null

        val destPath = fileStore.resolve(NS_LOCAL_COVERS, "${sourceId}_$sourceItemId.jpg")
        NSFileManager.defaultManager.removeItemAtPath(destPath, error = null)
        val written = NSFileManager.defaultManager.createFileAtPath(destPath, contents = data, attributes = null)
        return if (written) NSURL.fileURLWithPath(destPath).absoluteString else null
    }
}

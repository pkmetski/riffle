package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.data.AudiobookFilenames.MANIFEST
import com.riffle.core.domain.ContentCacheArtifact
import com.riffle.core.domain.ContentCacheArtifactKind
import com.riffle.core.domain.ContentCacheArtifactScanner
import com.riffle.core.domain.ContentCacheKey
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.timeIntervalSince1970

/**
 * iOS counterpart to [ContentCacheArtifactScannerImpl] — the same four cache namespaces
 * (EPUB, PDF, audiobook, CBZ) read through NSFileManager instead of `java.io.File`.
 *
 * Before this existed, [com.riffle.core.domain.ContentCacheCleaner] was JVM-only and iOS's
 * "Auto-clear cache after N days" setting was written and never read (#1071 §13).
 */
@OptIn(ExperimentalForeignApi::class)
class IosContentCacheArtifactScannerImpl(private val fileStore: FileStore) : ContentCacheArtifactScanner {

    override fun listArtifacts(): List<ContentCacheArtifact> =
        fileArtifacts(NS_EPUB_CACHE, ".epub", ContentCacheArtifactKind.Epub) +
            fileArtifacts(NS_PDF_CACHE, ".pdf", ContentCacheArtifactKind.Pdf) +
            audiobookArtifacts(NS_AUDIOBOOK_CACHE) +
            fileArtifacts(NS_CBZ_CACHE, ".cbz", ContentCacheArtifactKind.Cbz)

    override fun delete(artifact: ContentCacheArtifact): Boolean {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(artifact.path)) return false
        return manager.removeItemAtPath(artifact.path, error = null)
    }

    private fun fileArtifacts(
        namespace: String,
        extension: String,
        kind: ContentCacheArtifactKind,
    ): List<ContentCacheArtifact> {
        val root = fileStore.resolve(namespace, "")
        return IosFileEnumeration.relativePathsUnder(root)
            .filter { it.endsWith(extension) && it.contains('/') }
            .map { relative ->
                val path = "$root/$relative"
                ContentCacheArtifact(
                    key = ContentCacheKey(
                        sourceId = relative.substringBefore('/'),
                        itemId = relative.substringAfter('/').removeSuffix(extension),
                        kind = kind,
                    ),
                    path = path,
                    sizeBytes = IosFileEnumeration.fileSize(path),
                    evidenceLastModifiedAtMs = modifiedAtMs(path)?.takeIf { it > 0L },
                )
            }
    }

    private fun audiobookArtifacts(namespace: String): List<ContentCacheArtifact> {
        val root = fileStore.resolve(namespace, "")
        return IosFileEnumeration.relativePathsUnder(root)
            .filter { it.endsWith("/$MANIFEST") }
            .mapNotNull { relative ->
                val itemRelative = relative.removeSuffix("/$MANIFEST")
                if (!itemRelative.contains('/')) return@mapNotNull null
                val itemDir = "$root/$itemRelative"
                var newestMs = 0L
                IosFileEnumeration.relativePathsUnder(itemDir).forEach { child ->
                    val childModified = modifiedAtMs("$itemDir/$child") ?: 0L
                    if (childModified > newestMs) newestMs = childModified
                }
                ContentCacheArtifact(
                    key = ContentCacheKey(
                        sourceId = itemRelative.substringBefore('/'),
                        itemId = itemRelative.substringAfter('/'),
                        kind = ContentCacheArtifactKind.Audiobook,
                    ),
                    path = itemDir,
                    sizeBytes = IosAudiobookFiles.directorySize(itemDir),
                    evidenceLastModifiedAtMs = newestMs.takeIf { it > 0L },
                )
            }
    }

    private fun modifiedAtMs(path: String): Long? {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return null
        val date = attributes[NSFileModificationDate] as? NSDate ?: return null
        return (date.timeIntervalSince1970 * 1000.0).toLong()
    }
}

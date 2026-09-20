package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.StoredArtifactStore
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredMediaType
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDirectoryEnumerator
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize

/**
 * NSFileManager-backed [StoredArtifactStore]s — the iOS counterpart to the `LocalStore`/`File`
 * adapters inside Android's `DownloadsRepositoryImpl`. They only know how to read a namespace off
 * disk; every policy decision lives in the shared
 * [com.riffle.core.domain.StoredArtifactDownloadsRepository].
 */

/**
 * A file-per-item namespace — `<namespace>/<sourceId>/<itemId><extension>`, the layout every iOS
 * downloader writes. Enumerated recursively so item ids containing `/` survive the round trip,
 * matching Android's `walkTopDown()`.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosFileArtifactStore(
    private val fileStore: FileStore,
    private val namespace: String,
    private val extension: String,
    override val mediaType: StoredMediaType,
) : StoredArtifactStore {

    override fun list(): List<StoredItemArtifact> {
        val root = fileStore.resolve(namespace, "")
        return enumerateRelativePaths(root)
            .filter { it.endsWith(extension) && it.contains('/') }
            .map { relative ->
                StoredItemArtifact(
                    sourceId = relative.substringBefore('/'),
                    itemId = relative.substringAfter('/').removeSuffix(extension),
                    mediaType = mediaType,
                )
            }
    }

    override fun sizeOf(sourceId: String, itemId: String): Long = fileSizeAt(pathFor(sourceId, itemId))

    override fun delete(sourceId: String, itemId: String) {
        NSFileManager.defaultManager.removeItemAtPath(pathFor(sourceId, itemId), error = null)
    }

    override fun clear() {
        val root = fileStore.resolve(namespace, "")
        val manager = NSFileManager.defaultManager

        @Suppress("UNCHECKED_CAST")
        val children = manager.contentsOfDirectoryAtPath(root, error = null) as? List<String> ?: return
        children.forEach { manager.removeItemAtPath("$root/$it", error = null) }
    }

    private fun pathFor(sourceId: String, itemId: String): String =
        fileStore.resolve(namespace, "$sourceId/$itemId$extension")
}

/**
 * A directory-backed audiobook root — `<namespace>/<sourceId>/<itemId>/manifest.json` (ADR 0035).
 * The manifest is the atomic completion marker, so a partially downloaded item is deliberately
 * not listed, exactly as on Android.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAudiobookArtifactStore(
    private val fileStore: FileStore,
    private val namespace: String,
) : StoredArtifactStore {

    override val mediaType: StoredMediaType = StoredMediaType.Audiobook

    override fun list(): List<StoredItemArtifact> {
        val root = fileStore.resolve(namespace, "")
        return enumerateRelativePaths(root)
            .filter { it.endsWith("/$MANIFEST_NAME") }
            .mapNotNull { relative ->
                val itemRelative = relative.removeSuffix("/$MANIFEST_NAME")
                if (!itemRelative.contains('/')) return@mapNotNull null
                StoredItemArtifact(
                    sourceId = itemRelative.substringBefore('/'),
                    itemId = itemRelative.substringAfter('/'),
                    mediaType = mediaType,
                )
            }
    }

    override fun sizeOf(sourceId: String, itemId: String): Long =
        IosAudiobookFiles.directorySize(itemDir(sourceId, itemId))

    override fun delete(sourceId: String, itemId: String) {
        IosAudiobookFiles.deleteRecursively(itemDir(sourceId, itemId))
    }

    override fun clear() {
        val root = fileStore.resolve(namespace, "")
        val manager = NSFileManager.defaultManager

        @Suppress("UNCHECKED_CAST")
        val children = manager.contentsOfDirectoryAtPath(root, error = null) as? List<String> ?: return
        children.forEach { manager.removeItemAtPath("$root/$it", error = null) }
    }

    private fun itemDir(sourceId: String, itemId: String): String =
        IosAudiobookFiles.itemDir(fileStore.resolve(namespace, ""), sourceId, itemId)

    private companion object {
        const val MANIFEST_NAME = "manifest.json"
    }
}

/** Every path under [root], relative to it, recursively — NSFileManager's `walkTopDown()`. */
@OptIn(ExperimentalForeignApi::class)
private fun enumerateRelativePaths(root: String): List<String> {
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(root)) return emptyList()
    val enumerator: NSDirectoryEnumerator = manager.enumeratorAtPath(root) ?: return emptyList()
    val out = mutableListOf<String>()
    while (true) {
        val relative = enumerator.nextObject() as? String ?: break
        out += relative
    }
    return out
}

@OptIn(ExperimentalForeignApi::class)
private fun fileSizeAt(path: String): Long {
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return 0L
    return when (val size = attributes[NSFileSize]) {
        is Long -> size
        is Int -> size.toLong()
        is ULong -> size.toLong()
        is UInt -> size.toLong()
        is Number -> size.toLong()
        else -> 0L
    }
}

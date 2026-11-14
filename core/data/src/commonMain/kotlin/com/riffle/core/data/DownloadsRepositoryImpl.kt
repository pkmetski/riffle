package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.data.AudiobookFilenames.MANIFEST
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.StoredArtifactDownloadsRepository
import com.riffle.core.domain.StoredArtifactStore
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredMediaType
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Platform-neutral host for the shared [StoredArtifactDownloadsRepository] policy.
 *
 * Uses [FileStore] for path resolution and [FileSystem] for directory enumeration so there is no
 * Android or iOS import anywhere here. Android provides [FileSystem.SYSTEM] backed by java.io;
 * iOS provides [FileSystem.SYSTEM] backed by POSIX — behaviour is identical.
 */
class DownloadsRepositoryImpl(
    fileStore: FileStore,
    localAvailabilityEvents: LocalAvailabilityEvents = NoopLocalAvailabilityEvents,
    fileSystem: FileSystem = FileSystem.SYSTEM,
) : DownloadsRepository by StoredArtifactDownloadsRepository(
    downloadStores = listOf(
        OkioFileArtifactStore(fileStore, NS_EPUB_DOWNLOADS, ".epub", StoredMediaType.Epub, fileSystem),
        OkioFileArtifactStore(fileStore, NS_PDF_DOWNLOADS, ".pdf", StoredMediaType.Pdf, fileSystem),
        OkioFileArtifactStore(fileStore, NS_CBZ_DOWNLOADS, ".cbz", StoredMediaType.Cbz, fileSystem),
        OkioAudiobookArtifactStore(fileStore, NS_AUDIOBOOK_DOWNLOADS, fileSystem),
    ),
    cacheStores = listOf(
        OkioFileArtifactStore(fileStore, NS_EPUB_CACHE, ".epub", StoredMediaType.Epub, fileSystem),
        OkioFileArtifactStore(fileStore, NS_PDF_CACHE, ".pdf", StoredMediaType.Pdf, fileSystem),
        OkioFileArtifactStore(fileStore, NS_CBZ_CACHE, ".cbz", StoredMediaType.Cbz, fileSystem),
        OkioAudiobookArtifactStore(fileStore, NS_AUDIOBOOK_CACHE, fileSystem),
    ),
    localAvailabilityEvents = localAvailabilityEvents,
)

/**
 * A file-per-item namespace — `<namespace>/<sourceId>/<itemId><extension>`. Enumerated
 * recursively so item ids containing `/` survive the round trip, matching both platforms'
 * previous walk behaviour.
 */
internal class OkioFileArtifactStore(
    private val fileStore: FileStore,
    private val namespace: String,
    private val extension: String,
    override val mediaType: StoredMediaType,
    private val fileSystem: FileSystem,
) : StoredArtifactStore {

    override fun list(): List<StoredItemArtifact> {
        val root = fileStore.resolve(namespace).toPath()
        if (!fileSystem.exists(root)) return emptyList()
        return runCatching { fileSystem.listRecursively(root) }.getOrElse { emptySequence() }
            .filter { p ->
                fileSystem.metadata(p).isRegularFile && p.name.endsWith(extension)
            }
            .mapNotNull { p ->
                val rootStr = root.toString().trimEnd('/')
                val pStr = p.toString()
                if (!pStr.startsWith("$rootStr/")) return@mapNotNull null
                val relative = pStr.removePrefix("$rootStr/")
                if (!relative.contains('/')) return@mapNotNull null
                StoredItemArtifact(
                    sourceId = relative.substringBefore('/'),
                    itemId = relative.substringAfter('/').removeSuffix(extension),
                    mediaType = mediaType,
                )
            }
            .toList()
    }

    override fun sizeOf(sourceId: String, itemId: String): Long {
        val path = pathFor(sourceId, itemId).toPath()
        return if (fileSystem.exists(path)) fileSystem.metadata(path).size ?: 0L else 0L
    }

    override fun delete(sourceId: String, itemId: String) {
        runCatching { fileSystem.delete(pathFor(sourceId, itemId).toPath()) }
    }

    override fun clear() {
        val root = fileStore.resolve(namespace).toPath()
        if (!fileSystem.exists(root)) return
        runCatching { fileSystem.list(root) }.getOrElse { emptyList() }
            .forEach { p -> runCatching { fileSystem.deleteRecursively(p) } }
    }

    private fun pathFor(sourceId: String, itemId: String): String =
        fileStore.resolve(namespace, "$sourceId/$itemId$extension")
}

/**
 * A directory-backed audiobook root — `<namespace>/<sourceId>/<itemId>/manifest.json` (ADR 0035).
 * The manifest is the atomic completion marker, so a partially downloaded item is deliberately not
 * listed, exactly as on both platforms previously.
 */
internal class OkioAudiobookArtifactStore(
    private val fileStore: FileStore,
    private val namespace: String,
    private val fileSystem: FileSystem,
) : StoredArtifactStore {

    override val mediaType: StoredMediaType = StoredMediaType.Audiobook

    override fun list(): List<StoredItemArtifact> {
        val root = fileStore.resolve(namespace).toPath()
        if (!fileSystem.exists(root)) return emptyList()
        return runCatching { fileSystem.listRecursively(root) }.getOrElse { emptySequence() }
            .filter { p ->
                fileSystem.metadata(p).isRegularFile && p.name == MANIFEST
            }
            .mapNotNull { p ->
                val rootStr = root.toString().trimEnd('/')
                val pStr = p.toString()
                if (!pStr.startsWith("$rootStr/")) return@mapNotNull null
                val relative = pStr.removePrefix("$rootStr/")
                val itemRelative = relative.removeSuffix("/$MANIFEST")
                if (!itemRelative.contains('/')) return@mapNotNull null
                StoredItemArtifact(
                    sourceId = itemRelative.substringBefore('/'),
                    itemId = itemRelative.substringAfter('/'),
                    mediaType = StoredMediaType.Audiobook,
                )
            }
            .toList()
    }

    override fun sizeOf(sourceId: String, itemId: String): Long {
        val dir = itemDir(sourceId, itemId).toPath()
        return if (!fileSystem.exists(dir)) 0L
        else runCatching { fileSystem.listRecursively(dir) }.getOrElse { emptySequence() }
            .sumOf { p ->
                if (fileSystem.metadata(p).isRegularFile) fileSystem.metadata(p).size ?: 0L else 0L
            }
    }

    override fun delete(sourceId: String, itemId: String) {
        runCatching { fileSystem.deleteRecursively(itemDir(sourceId, itemId).toPath()) }
    }

    override fun clear() {
        val root = fileStore.resolve(namespace).toPath()
        if (!fileSystem.exists(root)) return
        runCatching { fileSystem.list(root) }.getOrElse { emptyList() }
            .forEach { p -> runCatching { fileSystem.deleteRecursively(p) } }
    }

    private fun itemDir(sourceId: String, itemId: String): String =
        fileStore.resolve(namespace, "$sourceId/$itemId")
}

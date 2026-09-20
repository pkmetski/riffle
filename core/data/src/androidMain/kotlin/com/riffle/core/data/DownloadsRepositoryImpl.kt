package com.riffle.core.data

import com.riffle.core.data.AudiobookFilenames.MANIFEST
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.LocalStore
import com.riffle.core.domain.StoredArtifactDownloadsRepository
import com.riffle.core.domain.StoredArtifactStore
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredMediaType
import java.io.File

/**
 * Android host for the shared [StoredArtifactDownloadsRepository] policy: it owns only the
 * `java.io.File` plumbing (six typed [LocalStore]s plus the two directory-backed audiobook
 * roots) and delegates every decision — cached-minus-downloaded, remove-download-also-removes-
 * cache, availability notification — to `core:domain` so iOS runs the same rules.
 */
class DownloadsRepositoryImpl(
    epubCacheStore: LocalStore,
    epubDownloadsStore: LocalStore,
    pdfCacheStore: LocalStore,
    pdfDownloadsStore: LocalStore,
    cbzCacheStore: LocalStore,
    cbzDownloadsStore: LocalStore,
    audiobookCacheDir: File,
    audiobookDownloadsDir: File,
    localAvailabilityEvents: LocalAvailabilityEvents = NoopLocalAvailabilityEvents,
) : DownloadsRepository by StoredArtifactDownloadsRepository(
    downloadStores = listOf(
        LocalStoreArtifactStore(epubDownloadsStore, StoredMediaType.Epub),
        LocalStoreArtifactStore(pdfDownloadsStore, StoredMediaType.Pdf),
        LocalStoreArtifactStore(cbzDownloadsStore, StoredMediaType.Cbz),
        AudiobookDirectoryArtifactStore(audiobookDownloadsDir),
    ),
    cacheStores = listOf(
        LocalStoreArtifactStore(epubCacheStore, StoredMediaType.Epub),
        LocalStoreArtifactStore(pdfCacheStore, StoredMediaType.Pdf),
        LocalStoreArtifactStore(cbzCacheStore, StoredMediaType.Cbz),
        AudiobookDirectoryArtifactStore(audiobookCacheDir),
    ),
    localAvailabilityEvents = localAvailabilityEvents,
)

/** Adapts a file-per-item [LocalStore] (epub/pdf/cbz) to the shared [StoredArtifactStore] seam. */
private class LocalStoreArtifactStore(
    private val store: LocalStore,
    override val mediaType: StoredMediaType,
) : StoredArtifactStore {
    override fun list(): List<StoredItemArtifact> =
        store.listItems().map { StoredItemArtifact(it.sourceId, it.itemId, mediaType) }

    override fun sizeOf(sourceId: String, itemId: String): Long =
        store.get(sourceId, itemId)?.length() ?: 0L

    override fun delete(sourceId: String, itemId: String) = store.delete(sourceId, itemId)

    override fun clear() = store.clear()
}

/**
 * Adapts a directory-backed audiobook root (`<root>/<sourceId>/<itemId>/manifest.json`, ADR 0035)
 * to the shared [StoredArtifactStore] seam. The manifest is the atomic completion marker, so a
 * partially downloaded item is deliberately not listed.
 */
private class AudiobookDirectoryArtifactStore(private val root: File) : StoredArtifactStore {
    override val mediaType: StoredMediaType = StoredMediaType.Audiobook

    override fun list(): List<StoredItemArtifact> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { sourceDir ->
                val prefix = sourceDir.absolutePath + File.separator
                sourceDir.walkTopDown()
                    .filter { it.isFile && it.name == MANIFEST }
                    .map { manifest ->
                        val itemDir = requireNotNull(manifest.parentFile) { "manifest without parent: $manifest" }
                        StoredItemArtifact(
                            sourceId = sourceDir.name,
                            itemId = itemDir.absolutePath.removePrefix(prefix),
                            mediaType = mediaType,
                        )
                    }
                    .toList()
            }
            ?: emptyList()

    override fun sizeOf(sourceId: String, itemId: String): Long {
        val dir = itemDir(sourceId, itemId)
        return if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    override fun delete(sourceId: String, itemId: String) {
        itemDir(sourceId, itemId).deleteRecursively()
    }

    override fun clear() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun itemDir(sourceId: String, itemId: String): File = root.resolve(sourceId).resolve(itemId)
}

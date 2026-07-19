package com.riffle.app.feature.library

import com.riffle.app.feature.reader.toTocEntries
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.TocEntry
import com.riffle.core.domain.TocRepository
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.use
import org.readium.r2.streamer.PublicationOpener
import javax.inject.Inject

class ExtractEpubTocUseCase @Inject constructor(
    private val epubRepository: EpubRepository,
    private val publicationOpener: PublicationOpener,
    private val assetRetriever: AssetRetriever,
    private val tocRepository: TocRepository,
) {
    suspend operator fun invoke(item: LibraryItem): List<TocEntry> {
        // Use "unknown" when the server doesn't provide an inode (ABS < v2.36 omits
        // ebookFile.ino from the library-items list). The cache key still works; it
        // just won't auto-invalidate when the file is replaced on disk, which is an
        // acceptable trade-off for older servers.
        val inode = item.ebookFileIno ?: "unknown"

        val cached = tocRepository.getCachedToc(item.sourceId, item.id)
        // Only trust a cache hit that has entries. An empty cached list is treated as a miss so a
        // transient extraction failure (e.g. a Readium parse hiccup on first open) doesn't poison
        // the cache forever — especially under the "unknown" inode key used for ABS < v2.36, where
        // the key never changes and there's no other invalidation trigger.
        if (cached != null && cached.first == inode && cached.second.isNotEmpty()) return cached.second

        val file = when (val r = epubRepository.openEpub(item)) {
            is EpubOpenResult.Success -> r.epubFile
            else -> return emptyList()
        }

        val url = AbsoluteUrl("file://${file.absolutePath}") ?: return emptyList()
        val asset = when (val r = assetRetriever.retrieve(url)) {
            is Try.Success -> r.value
            is Try.Failure -> return emptyList()
        }
        val publication = when (val r = publicationOpener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> r.value
            is Try.Failure -> return emptyList()
        }

        return publication.use {
            val entries = it.tableOfContents.toTocEntries()
            // Don't persist an empty TOC — it's almost always a transient parse failure, and
            // caching it would prevent a healthy re-extract on the next open.
            if (entries.isNotEmpty()) {
                tocRepository.saveToc(item.sourceId, item.id, inode, entries)
            }
            entries
        }
    }
}

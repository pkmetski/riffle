package com.riffle.app.feature.library

import com.riffle.app.feature.reader.toTocEntries
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.LibraryItem
import com.riffle.core.domain.TocEntry
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
        val inode = item.ebookFileIno ?: return emptyList()

        val cached = tocRepository.getCachedToc(item.serverId, item.id)
        if (cached != null && cached.first == inode) return cached.second

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
            tocRepository.saveToc(item.serverId, item.id, inode, entries)
            entries
        }
    }
}

package com.riffle.app.feature.library

import com.riffle.app.feature.reader.toTocEntries
import com.riffle.core.domain.EpubMetadataExtractor
import com.riffle.core.domain.EpubOpenResult
import com.riffle.core.domain.EpubRepository
import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.PublicationMetricsRepository
import com.riffle.core.domain.TocRepository
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.TocEntry
import java.io.File
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.ceil
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.use
import org.readium.r2.streamer.PublicationOpener
import javax.inject.Inject

private const val READIUM_EPUB_POSITION_PAGE_LENGTH = 1024L

/**
 * Counts positions using Readium's default EPUB strategy from ZIP entry metadata.
 *
 * Readium divides each spine entry's compressed size (uncompressed size for stored entries) into
 * 1 KiB positions. Reading the central directory directly avoids both materializing Locators and
 * opening/closing every Readium Resource; Resource.close() schedules asynchronous global cleanup,
 * which can overlap the reader opened immediately from details.
 */
internal fun countEpubPositionsFromArchive(
    epubFile: File,
    readingOrderHrefs: List<String>,
    layout: Layout?,
): Int? {
    if (layout == Layout.FIXED) {
        return readingOrderHrefs.size.takeIf { it > 0 }
    }

    val total = runCatching {
        ZipFile(epubFile).use { archive ->
            readingOrderHrefs.sumOf { href ->
                val path = runCatching { URI(href).path }
                    .getOrNull()
                    ?.removePrefix("/")
                    ?: href.substringBefore('#').substringBefore('?').removePrefix("/")
                val entry = archive.getEntry(path) ?: return@sumOf 0
                val length = if (entry.method == ZipEntry.STORED) entry.size else entry.compressedSize
                if (length < 0L) return@sumOf 1
                ceil(length.toDouble() / READIUM_EPUB_POSITION_PAGE_LENGTH.toDouble())
                    .toInt()
                    .coerceAtLeast(1)
            }
        }
    }.getOrNull() ?: return null
    return total.takeIf { it > 0 }
}

class ExtractEpubTocUseCase @Inject constructor(
    private val epubRepository: EpubRepository,
    private val publicationOpener: PublicationOpener,
    private val assetRetriever: AssetRetriever,
    private val tocRepository: TocRepository,
    private val publicationMetricsRepository: PublicationMetricsRepository,
) {
    data class Details(
        val tocEntries: List<TocEntry>,
        val totalPositions: Int?,
        val epubVersion: String? = null,
    )

    suspend operator fun invoke(item: LibraryItem): List<TocEntry> =
        extractDetails(item).tocEntries

    suspend fun extractDetails(item: LibraryItem): Details {
        // Use "unknown" when the server doesn't provide an inode (ABS < v2.36 omits
        // ebookFile.ino from the library-items list). The cache key still works; it
        // just won't auto-invalidate when the file is replaced on disk, which is an
        // acceptable trade-off for older servers.
        val inode = item.ebookFileIno ?: "unknown"

        val cached = tocRepository.getCachedToc(item.sourceId, item.id)
        val cachedMetrics = publicationMetricsRepository.get(item.sourceId, item.id)
        val matchingCachedEntries = cached
            ?.takeIf { it.first == inode && it.second.isNotEmpty() }
            ?.second
        val matchingPositionCount = cachedMetrics
            ?.takeIf { it.ebookFileIno == inode }
            ?.totalPositions
            ?.takeIf { it > 0 }
        val matchingEpubVersion = cachedMetrics
            ?.takeIf { it.ebookFileIno == inode }
            ?.epubVersion
        // Only trust a cache hit that has entries. An empty cached list is treated as a miss so a
        // transient extraction failure (e.g. a Readium parse hiccup on first open) doesn't poison
        // the cache forever — especially under the "unknown" inode key used for ABS < v2.36, where
        // the key never changes and there's no other invalidation trigger.
        if (matchingCachedEntries != null && matchingPositionCount != null && matchingEpubVersion != null) {
            return Details(matchingCachedEntries, matchingPositionCount, matchingEpubVersion)
        }

        val file = when (val r = epubRepository.openEpub(item)) {
            is EpubOpenResult.Success -> r.epubFile
            else -> return Details(matchingCachedEntries.orEmpty(), matchingPositionCount, matchingEpubVersion)
        }

        // Use "" as a sentinel meaning "extracted but version attribute absent", so a version-less
        // EPUB doesn't permanently fail the cache-hit guard (which treats null as "never extracted").
        val extractedEpubVersion = EpubMetadataExtractor.extract(file).epubVersion ?: ""

        val url = AbsoluteUrl("file://${file.absolutePath}")
            ?: return Details(matchingCachedEntries.orEmpty(), matchingPositionCount, matchingEpubVersion)
        val asset = when (val r = assetRetriever.retrieve(url)) {
            is Try.Success -> r.value
            is Try.Failure -> return Details(matchingCachedEntries.orEmpty(), matchingPositionCount, matchingEpubVersion)
        }
        val publication = when (val r = publicationOpener.open(asset, allowUserInteraction = false)) {
            is Try.Success -> r.value
            is Try.Failure -> return Details(matchingCachedEntries.orEmpty(), matchingPositionCount, matchingEpubVersion)
        }

        val details = publication.use {
            Details(
                tocEntries = it.tableOfContents.toTocEntries(),
                totalPositions = runCatching {
                    countEpubPositionsFromArchive(
                        epubFile = file,
                        readingOrderHrefs = it.readingOrder.map { link -> link.href.toString() },
                        layout = it.metadata.layout,
                    )
                }.getOrNull(),
                epubVersion = extractedEpubVersion,
            )
        }
        if (details.tocEntries.isNotEmpty()) {
            tocRepository.saveToc(item.sourceId, item.id, inode, details.tocEntries)
        }
        if (details.totalPositions != null) {
            publicationMetricsRepository.save(
                item.sourceId,
                item.id,
                PublicationMetrics(
                    ebookFileIno = inode,
                    totalPositions = details.totalPositions,
                    epubVersion = details.epubVersion,
                ),
            )
        }
        return details
    }
}

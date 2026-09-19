package com.riffle.shared.reader

import com.riffle.core.domain.PublicationMetrics
import com.riffle.core.domain.PublicationMetricsRepository
import com.riffle.core.domain.TocRepository
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.TocEntry
import com.riffle.feature.library.EpubDetails
import com.riffle.feature.library.EpubTocExtractor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * iOS [EpubTocExtractor]: opens a headless Readium Swift [Publication] via
 * [IosPublicationInspector] (no navigator/view controller) to read the TOC and position count,
 * then caches the result exactly like Android's ExtractEpubTocUseCase — same
 * (sourceId, itemId, ebookFileIno) cache-key scheme against [TocRepository]/
 * [PublicationMetricsRepository], so the two platforms share cache semantics even though the
 * underlying Readium runtime differs.
 *
 * [epubVersion] is left null on iOS: Readium Swift's Publication doesn't expose the raw OPF
 * `<package version="...">` attribute the way Android's EpubMetadataExtractor parses it directly
 * from the zip, and epubVersion is only a secondary cache-metadata field (never read back into
 * TOC/position logic) — not worth a second raw-XML parse for.
 */
internal class IosEpubTocExtractor(
    private val inspector: IosPublicationInspector,
    private val epubDownloader: IosEpubDownloader,
    private val tocRepository: TocRepository,
    private val publicationMetricsRepository: PublicationMetricsRepository,
) : EpubTocExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun extract(item: LibraryItem): List<TocEntry> = extractDetails(item).tocEntries

    override suspend fun extractDetails(item: LibraryItem): EpubDetails {
        val inode = item.ebookFileIno ?: "unknown"

        val cached = tocRepository.getCachedToc(item.sourceId, item.id)
        val cachedMetrics = publicationMetricsRepository.get(item.sourceId, item.id)
        val matchingCachedEntries = cached?.takeIf { it.first == inode && it.second.isNotEmpty() }?.second
        val matchingPositionCount = cachedMetrics?.takeIf { it.ebookFileIno == inode }?.totalPositions?.takeIf { it > 0 }
        if (matchingCachedEntries != null && matchingPositionCount != null) {
            return EpubDetails(matchingCachedEntries, matchingPositionCount, null)
        }

        val filePath = epubDownloader.localPath(item)
            ?: return EpubDetails(matchingCachedEntries.orEmpty(), matchingPositionCount, null)

        val resultJson = inspectEpub(filePath)
            ?: return EpubDetails(matchingCachedEntries.orEmpty(), matchingPositionCount, null)

        val obj = runCatching { json.parseToJsonElement(resultJson).let { it as JsonObject } }.getOrNull()
            ?: return EpubDetails(matchingCachedEntries.orEmpty(), matchingPositionCount, null)
        val tocEntries = runCatching {
            json.decodeFromString<List<TocEntry>>(obj["tocJson"]?.jsonPrimitive?.content ?: "[]")
        }.getOrDefault(emptyList())
        val totalPositions = obj["totalPositions"]?.jsonPrimitive?.content?.toIntOrNull()

        if (tocEntries.isNotEmpty()) {
            tocRepository.saveToc(item.sourceId, item.id, inode, tocEntries)
        }
        if (totalPositions != null && totalPositions > 0) {
            publicationMetricsRepository.save(
                item.sourceId,
                item.id,
                PublicationMetrics(ebookFileIno = inode, totalPositions = totalPositions, epubVersion = null),
            )
        }

        return EpubDetails(
            tocEntries.ifEmpty { matchingCachedEntries.orEmpty() },
            totalPositions?.takeIf { it > 0 } ?: matchingPositionCount,
            null,
        )
    }

    private suspend fun inspectEpub(filePath: String): String? = suspendCancellableCoroutine { cont ->
        inspector.inspectEpub(filePath) { resultJson -> cont.resume(resultJson) }
    }
}

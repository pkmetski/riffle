package com.riffle.core.catalog.abs

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import com.riffle.core.catalog.CatalogProgress
import com.riffle.core.catalog.CatalogRoot
import com.riffle.core.catalog.SortKey
import com.riffle.core.models.EbookFormat
import com.riffle.core.network.AbsCoverUrl
import com.riffle.core.network.NetworkLibrary
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkServerProgress

/**
 * ABS network-envelope → Catalog-model mappers, shared by [AbsCommonCatalog] (commonMain, the
 * browse + progress-peer half every platform runs) and `AbsCatalog` (jvmMain, which adds the
 * file-transfer and import halves). They live here as top-level `internal` functions rather than
 * as private members of either class so the two cannot drift: a second copy of
 * `toCatalogItem`/`toCatalogProgress` is exactly the divergence AGENTS.md forbids.
 *
 * `baseUrl` is threaded in explicitly because cover URLs are the one mapping that needs per-Source
 * configuration; everything else is a pure projection.
 */
internal fun NetworkLibrary.toCatalogRoot(): CatalogRoot = CatalogRoot(
    id = id,
    name = name,
    mediaType = mediaType,
    isUnsupported = mediaType == "podcast",
    importFolderId = folders.firstOrNull()?.id,
)

internal fun NetworkLibraryItem.toCatalogItem(baseUrl: String): CatalogItem = CatalogItem(
    id = id,
    rootId = libraryId,
    title = title,
    author = author,
    coverUrl = AbsCoverUrl.of(baseUrl, id, updatedAt),
    ebookFormat = ebookFormat.toCatalogFormat(hasAudio = hasAudio),
    hasAudio = hasAudio,
    audioDurationSec = audioDurationSec,
    ebookFileIno = ebookFileIno,
    description = description,
    seriesName = seriesName,
    publishedYear = publishedYear,
    genres = genres,
    publisher = publisher,
    language = language,
    addedAt = addedAt,
    isbn = isbn,
    asin = asin,
    readingProgress = readingProgress,
    updatedAt = updatedAt,
    path = path,
    relPath = relPath,
)

internal fun NetworkServerProgress.toCatalogProgress(itemId: String): CatalogProgress = CatalogProgress(
    itemId = itemId,
    ebookLocation = ebookLocation.takeIf { it.isNotEmpty() },
    ebookProgress = ebookProgress,
    audioCurrentTime = currentTime,
    audioDuration = duration,
    // Derive from position data via the shared helper, not ABS's sticky isFinished/finishedAt
    // flags: ABS does not auto-clear those when another device advances the position, so trusting
    // them would pin unifiedLibraryFraction() to 1f even when ebookProgress = 0.6. NetworkServerProgress
    // carries no flags, so the sticky fallback is unused here — but sharing the derivation with
    // pullAllProgress guarantees the per-item and bulk pulls always agree on Finished state.
    isFinished = CatalogProgress.deriveIsFinished(ebookProgress, currentTime, duration),
    lastUpdate = lastUpdate,
)

internal fun EbookFormat.toCatalogFormat(hasAudio: Boolean = false): BookFormat = when (this) {
    EbookFormat.Epub -> BookFormat.Epub
    EbookFormat.Pdf -> BookFormat.Pdf
    EbookFormat.Cbz -> BookFormat.Cbz
    EbookFormat.Unsupported -> if (hasAudio) BookFormat.Audiobook else BookFormat.Unsupported
}

internal fun absComparatorFor(sort: SortKey): Comparator<CatalogItem> = when (sort) {
    SortKey.TITLE -> compareBy { it.title.lowercase() }
    SortKey.AUTHOR -> compareBy { it.author.lowercase() }
    SortKey.ADDED_AT -> compareByDescending { it.addedAt ?: 0L }
    SortKey.PUBLISHED_YEAR -> compareBy { it.publishedYear ?: "" }
    // Last-opened is a per-device local concept ABS doesn't track. Repositories (#434) apply
    // this ordering on top of catalog output; the Catalog layer refuses so silent fall-through
    // to title-order can't mask the missing local-store lookup.
    SortKey.RECENTLY_OPENED -> throw CatalogException.UnsupportedFormat(
        "SortKey.RECENTLY_OPENED is a local ordering — apply it above the Catalog layer",
    )
}

internal fun <T> List<T>.absPageOf(page: Int, pageSize: Int): List<T> {
    val from = (page * pageSize).coerceAtLeast(0)
    if (from >= size) return emptyList()
    val to = (from + pageSize).coerceAtMost(size)
    return subList(from, to)
}

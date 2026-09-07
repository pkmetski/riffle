package com.riffle.core.data

import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem

/**
 * Canonical `library_items` row → domain mapping, shared by the Android repository and the iOS
 * observers so the two platforms can never derive a different [LibraryItem] from the same row.
 */
internal fun LibraryItemEntity.toDomainLibraryItem() = LibraryItem(
    id = id,
    sourceId = sourceId,
    libraryId = libraryId,
    title = title,
    author = author,
    coverUrl = coverUrl,
    readingProgress = readingProgress,
    isCached = false,
    isDownloaded = false,
    ebookFormat = EbookFormat.from(ebookFormat),
    ebookFileIno = ebookFileIno,
    hasAudio = hasAudio,
    audioDurationSec = audioDurationSec,
    description = description,
    seriesName = if (seriesName != null && !seriesSequence.isNullOrBlank()) "$seriesName #$seriesSequence" else seriesName,
    publishedYear = publishedYear,
    genres = genres.split(",").filter { it.isNotEmpty() },
    publisher = publisher,
    language = language,
    lastOpenedAt = lastOpenedAt,
    addedAt = addedAt,
    isbn = isbn,
    asin = asin,
    pageCount = pageCount,
)

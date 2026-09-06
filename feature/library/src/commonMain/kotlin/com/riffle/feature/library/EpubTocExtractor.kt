package com.riffle.feature.library

import com.riffle.core.models.LibraryItem
import com.riffle.core.models.TocEntry

data class EpubDetails(
    val tocEntries: List<TocEntry>,
    val totalPositions: Int?,
    val epubVersion: String? = null,
)

interface EpubTocExtractor {
    suspend fun extract(item: LibraryItem): List<TocEntry>
    suspend fun extractDetails(item: LibraryItem): EpubDetails
}

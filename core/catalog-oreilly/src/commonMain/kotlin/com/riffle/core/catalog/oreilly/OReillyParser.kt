package com.riffle.core.catalog.oreilly

import com.riffle.core.catalog.BookFormat
import com.riffle.core.catalog.CatalogItem
import kotlinx.serialization.json.Json

/**
 * Pure JSON → domain mapping for the O'Reilly catalog. Kept free of any HTTP so it is unit-testable
 * against captured fixtures. [OReillyCatalog] owns the network; this owns the shapes.
 */
internal object OReillyParser {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun parseSearch(body: String, rootId: String): List<CatalogItem> =
        json.decodeFromString(OReillySearchResponse.serializer(), body)
            .results
            .filter { it.bestId() != null }
            .filter { it.matchesRoot(rootId) }
            .map { it.toCatalogItem(rootId) }

    fun parseBookDetail(body: String): OReillyBookDetail =
        json.decodeFromString(OReillyBookDetail.serializer(), body)

    fun parseSpine(body: String): OReillySpineResponse =
        json.decodeFromString(OReillySpineResponse.serializer(), body)

    fun parseFiles(body: String): OReillyFilesResponse =
        json.decodeFromString(OReillyFilesResponse.serializer(), body)

    fun parseVideoToc(body: String): OReillyVideoTocResponse =
        json.decodeFromString(OReillyVideoTocResponse.serializer(), body)

    fun parseVideoClip(body: String): OReillyVideoClip =
        json.decodeFromString(OReillyVideoClip.serializer(), body)

    fun parseKalturaConfig(body: String): OReillyKalturaConfig =
        json.decodeFromString(OReillyKalturaConfig.serializer(), body)

    fun parseKalturaSession(body: String): OReillyKalturaSession =
        json.decodeFromString(OReillyKalturaSession.serializer(), body)

    fun parseReadingHistory(body: String): OReillyReadingHistoryResponse =
        json.decodeFromString(OReillyReadingHistoryResponse.serializer(), body)

    /**
     * Maps a reading-history item to a [CatalogItem] with [CatalogItem.readingProgress] set from
     * the server-reported [OReillyReadingHistoryItem.percentComplete] (0–100 → 0..1).
     * Returns null when the item has no usable id or no recorded progress.
     */
    fun readingHistoryItemToCatalogItem(item: OReillyReadingHistoryItem): CatalogItem? {
        val id = item.bestId() ?: return null
        val pct = item.percentComplete ?: return null
        if (pct <= 0.0) return null
        val rootId = if (item.isAudiobook) OReillyRoots.AUDIOBOOKS else OReillyRoots.BOOKS
        return CatalogItem(
            id = id,
            rootId = rootId,
            title = item.title,
            author = item.authors.joinToString(", "),
            coverUrl = item.coverUrl?.takeIf { it.isNotBlank() } ?: OReillyApi.coverUrl(id),
            ebookFormat = if (item.isAudiobook) BookFormat.Audiobook else BookFormat.Epub,
            hasAudio = item.isAudiobook,
            readingProgress = (pct / 100.0).toFloat().coerceIn(0f, 1f),
        )
    }

    /** True when a search hit belongs to the requested root (books vs audiobooks). */
    private fun OReillySearchHit.matchesRoot(rootId: String): Boolean = when (rootId) {
        OReillyRoots.AUDIOBOOKS -> isAudiobook
        OReillyRoots.BOOKS -> !isAudiobook
        else -> true
    }

    private fun OReillySearchHit.toCatalogItem(rootId: String): CatalogItem {
        val isAudio = rootId == OReillyRoots.AUDIOBOOKS
        return CatalogItem(
            id = bestId()!!,
            rootId = rootId,
            title = title,
            author = authors.joinToString(", "),
            // Ignore the search hit's low-res `cover_url`; request the full-resolution cover instead.
            coverUrl = OReillyApi.coverUrl(bestId()!!),
            ebookFormat = if (isAudio) BookFormat.Audiobook else BookFormat.Epub,
            hasAudio = isAudio,
            // duration_seconds is -1 for books; only meaningful for audiobooks.
            audioDurationSec = if (isAudio) durationSeconds.coerceAtLeast(0.0) else 0.0,
            description = description,
            publishedYear = issued?.take(4)?.takeIf { it.isNotBlank() },
            publisher = publishers.firstOrNull(),
            language = language,
            isbn = isbn,
            pageCount = virtualPages.takeIf { it > 0 },
        )
    }

    /**
     * Map v2 metadata to a [CatalogItem]. [coverUrl] is supplied by the caller (metadata has no
     * cover field). Authors/publisher aren't in the metadata — the caller carries them over from
     * the search listing when available.
     */
    fun bookDetailToCatalogItem(
        id: String,
        detail: OReillyBookDetail,
        coverUrl: String?,
        author: String = "",
    ): CatalogItem {
        val isAudio = detail.isAudiobook
        return CatalogItem(
            id = id,
            rootId = if (isAudio) OReillyRoots.AUDIOBOOKS else OReillyRoots.BOOKS,
            title = detail.title,
            author = author,
            coverUrl = coverUrl,
            ebookFormat = if (isAudio) BookFormat.Audiobook else BookFormat.Epub,
            hasAudio = isAudio,
            audioDurationSec = detail.totalRunningTimeSecs ?: 0.0,
            description = detail.descriptionHtml,
            publishedYear = detail.publicationDate?.take(4)?.takeIf { it.isNotBlank() },
            language = detail.language,
            isbn = detail.isbn,
            pageCount = (detail.pageCount.takeIf { it > 0 } ?: detail.virtualPages).takeIf { it > 0 },
        )
    }
}

/** Source-local root ids; mirror [com.riffle.core.domain.OReillyWebSourceDescriptor.defaultLibraries]. */
object OReillyRoots {
    const val BOOKS = "books"
    const val AUDIOBOOKS = "audiobooks"
}

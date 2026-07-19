package com.riffle.core.network.model

import com.riffle.core.models.EbookFormat
import com.riffle.core.network.NetworkLibraryItem
import com.riffle.core.network.NetworkPlaylist
import kotlinx.serialization.Serializable

@Serializable
internal data class AbsPlaylistsResponse(val results: List<AbsPlaylistDto>) {

    @Serializable
    data class AbsPlaylistDto(
        val id: String,
        val libraryId: String,
        val name: String,
        val items: List<AbsPlaylistItemDto> = emptyList(),
    )

    @Serializable
    data class AbsPlaylistItemDto(
        val libraryItemId: String,
        val libraryItem: AbsPlaylistLibraryItemDto? = null,
    )

    @Serializable
    data class AbsPlaylistLibraryItemDto(
        val id: String,
        val libraryId: String,
        val media: AbsPlaylistMediaDto,
        val userMediaProgress: AbsPlaylistProgressDto? = null,
    )

    @Serializable
    data class AbsPlaylistMediaDto(
        val metadata: AbsPlaylistMetadataDto,
        val ebookFormat: String? = null,
        val ebookFile: AbsPlaylistEbookFileDto? = null,
    )

    @Serializable
    data class AbsPlaylistEbookFileDto(val ino: String = "")

    @Serializable
    data class AbsPlaylistMetadataDto(
        val title: String = "",
        val authorName: String = "",
        val description: String? = null,
        val seriesName: String? = null,
        val publishedYear: String? = null,
        val genres: List<String> = emptyList(),
        val publisher: String? = null,
    )

    @Serializable
    data class AbsPlaylistProgressDto(
        val progress: Float = 0f,
        val ebookProgress: Float? = null,
    )
}

internal fun AbsPlaylistsResponse.AbsPlaylistDto.toNetworkPlaylist(): NetworkPlaylist =
    NetworkPlaylist(
        id = id,
        libraryId = libraryId,
        name = name,
        bookIds = items.map { it.libraryItemId }.toSet(),
        items = items.mapNotNull { entry ->
            val li = entry.libraryItem ?: return@mapNotNull null
            val progress = li.userMediaProgress?.ebookProgress
                ?: li.userMediaProgress?.progress
            NetworkLibraryItem(
                id = li.id,
                libraryId = li.libraryId,
                title = li.media.metadata.title,
                author = li.media.metadata.authorName,
                readingProgress = progress,
                ebookFormat = EbookFormat.from(li.media.ebookFormat),
                ebookFileIno = li.media.ebookFile?.ino?.takeIf { it.isNotEmpty() },
                description = li.media.metadata.description,
                seriesName = li.media.metadata.seriesName,
                publishedYear = li.media.metadata.publishedYear,
                genres = li.media.metadata.genres,
                publisher = li.media.metadata.publisher,
            )
        },
    )

package com.riffle.core.network.model

import com.riffle.core.models.EbookFormat
import com.riffle.core.network.NetworkCollection
import com.riffle.core.network.NetworkLibraryItem
import kotlinx.serialization.Serializable

@Serializable
internal data class AbsCollectionsResponse(val results: List<AbsCollectionDto>) {

    @Serializable
    data class AbsCollectionDto(
        val id: String,
        val name: String,
        val libraryId: String,
        val books: List<AbsCollectionBookDto> = emptyList(),
    )

    @Serializable
    data class AbsCollectionBookDto(
        val id: String,
        val libraryId: String,
        val media: AbsCollectionMediaDto,
        val userMediaProgress: AbsCollectionProgressDto? = null,
    )

    @Serializable
    data class AbsCollectionMediaDto(
        val metadata: AbsCollectionMetadataDto,
        val ebookFormat: String? = null,
        val ebookFile: AbsCollectionEbookFileDto? = null,
    )

    @Serializable
    data class AbsCollectionEbookFileDto(
        val ino: String = "",
    )

    @Serializable
    data class AbsCollectionMetadataDto(
        val title: String = "",
        val authorName: String = "",
        val description: String? = null,
        val seriesName: String? = null,
        val publishedYear: String? = null,
        val genres: List<String> = emptyList(),
        val publisher: String? = null,
    )

    @Serializable
    data class AbsCollectionProgressDto(
        val progress: Float = 0f,
        val ebookProgress: Float? = null,
    )
}

internal fun AbsCollectionsResponse.AbsCollectionDto.toNetworkCollection(): NetworkCollection =
    NetworkCollection(
        id = id,
        libraryId = libraryId,
        name = name,
        items = books.map { book ->
            val progress = book.userMediaProgress?.ebookProgress
                ?: book.userMediaProgress?.progress
            NetworkLibraryItem(
                id = book.id,
                libraryId = book.libraryId,
                title = book.media.metadata.title,
                author = book.media.metadata.authorName,
                readingProgress = progress,
                ebookFormat = EbookFormat.from(book.media.ebookFormat),
                ebookFileIno = book.media.ebookFile?.ino?.takeIf { it.isNotEmpty() },
                description = book.media.metadata.description,
                seriesName = book.media.metadata.seriesName,
                publishedYear = book.media.metadata.publishedYear,
                genres = book.media.metadata.genres,
                publisher = book.media.metadata.publisher,
            )
        },
    )

package com.riffle.core.catalog.komga

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of Komga's REST DTOs — only the fields Riffle actually reads. `ignoreUnknownKeys` in the
 * [KomgaJson] instance lets the server keep evolving without breaking parse.
 */
@Serializable
internal data class KomgaLibraryDto(
    val id: String,
    val name: String,
    val unavailable: Boolean = false,
)

@Serializable
internal data class KomgaBookMediaDto(
    val mediaType: String? = null,
    val pagesCount: Int? = null,
    val status: String? = null,
    /**
     * Komga's authoritative "what kind of book is this" tag: `DIVINA` for comic archives
     * (CBZ/CBR/EPUB-with-fixed-layout-images), `EPUB` for reflowable EPUBs, `PDF` for PDFs.
     * Present since Komga 1.x; older installs may leave it null.
     */
    val mediaProfile: String? = null,
)

@Serializable
internal data class KomgaBookMetadataDto(
    val title: String? = null,
    val summary: String? = null,
    val authors: List<KomgaAuthorDto> = emptyList(),
    val tags: List<String> = emptyList(),
    val releaseDate: String? = null,
    val isbn: String? = null,
    val links: List<KomgaLinkDto> = emptyList(),
    /** Human-facing sequence label ("1", "2.5"). Komga leaves this blank on non-series books. */
    val number: String? = null,
    val numberSort: Float? = null,
)

@Serializable
internal data class KomgaAuthorDto(
    val name: String = "",
    val role: String = "",
)

@Serializable
internal data class KomgaLinkDto(
    val label: String = "",
    val url: String = "",
)

@Serializable
internal data class KomgaBookDto(
    val id: String,
    val libraryId: String,
    val seriesId: String? = null,
    val name: String = "",
    val url: String? = null,
    val number: Float? = null,
    val created: String? = null,
    val lastModified: String? = null,
    val fileLastModified: String? = null,
    val sizeBytes: Long? = null,
    val media: KomgaBookMediaDto = KomgaBookMediaDto(),
    val metadata: KomgaBookMetadataDto = KomgaBookMetadataDto(),
    @SerialName("seriesTitle") val seriesTitle: String? = null,
)

@Serializable
internal data class KomgaSeriesMetadataDto(
    val title: String? = null,
    val titleSort: String? = null,
    val summary: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val totalBookCount: Int? = null,
)

@Serializable
internal data class KomgaSeriesDto(
    val id: String,
    val libraryId: String,
    val name: String = "",
    val booksCount: Int = 0,
    val metadata: KomgaSeriesMetadataDto = KomgaSeriesMetadataDto(),
)

/**
 * Komga readlist — the server-side "ordered collection of books" model. A single readlist may
 * span multiple libraries; Riffle uses one shared readlist named "To Read" per Komga server.
 */
@Serializable
internal data class KomgaReadListDto(
    val id: String,
    val name: String,
    val summary: String = "",
    val bookIds: List<String> = emptyList(),
    /** True when Komga hid one or more books because the caller lacks access. */
    val filtered: Boolean = false,
    /**
     * Present since Komga 1.19+. Non-admin users can only modify readlists they own — a
     * mismatched owner is why `PATCH /api/v1/readlists/{id}` returns 403 for a readlist that
     * this user *can* read. Null on older Komga versions (no ownership concept).
     */
    val ownerId: String? = null,
)

/** Subset of Komga's `/api/v1/users/me` response — only [id] is needed to detect readlist ownership. */
@Serializable
internal data class KomgaCurrentUserDto(
    val id: String,
)

@Serializable
internal data class KomgaReadListCreateDto(
    val name: String,
    val summary: String = "",
    val bookIds: List<String> = emptyList(),
)

@Serializable
internal data class KomgaReadListUpdateDto(
    val bookIds: List<String>,
)

@Serializable
internal data class KomgaPageDto<T>(
    val content: List<T> = emptyList(),
    val number: Int = 0,
    val size: Int = 0,
    val totalPages: Int = 0,
    val totalElements: Int = 0,
    val first: Boolean = true,
    val last: Boolean = true,
    val empty: Boolean = true,
)

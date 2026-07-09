package com.riffle.core.domain

/**
 * Metadata pulled from an EPUB's OPF `<metadata>` block plus the cover image bytes located via
 * `<manifest item properties="cover-image">` (EPUB3) or `<meta name="cover">` (EPUB2). Every field
 * is nullable — an EPUB with an empty `<metadata>` block is well-formed and yields an all-nulls
 * result rather than an exception.
 */
data class EpubMetadata(
    val title: String?,
    val author: String?,
    val language: String?,
    val publisher: String?,
    val publishedYear: String?,
    val description: String?,
    val isbn: String?,
    val asin: String?,
    val seriesName: String?,
    val genres: List<String>,
    val coverBytes: ByteArray?,
    val coverExtension: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EpubMetadata) return false
        return title == other.title && author == other.author && language == other.language &&
            publisher == other.publisher && publishedYear == other.publishedYear &&
            description == other.description && isbn == other.isbn && asin == other.asin &&
            seriesName == other.seriesName && genres == other.genres &&
            coverExtension == other.coverExtension &&
            (coverBytes?.contentEquals(other.coverBytes) ?: (other.coverBytes == null))
    }

    override fun hashCode(): Int {
        var r = title.hashCode()
        r = 31 * r + (author?.hashCode() ?: 0)
        r = 31 * r + (language?.hashCode() ?: 0)
        r = 31 * r + (publisher?.hashCode() ?: 0)
        r = 31 * r + (publishedYear?.hashCode() ?: 0)
        r = 31 * r + (description?.hashCode() ?: 0)
        r = 31 * r + (isbn?.hashCode() ?: 0)
        r = 31 * r + (asin?.hashCode() ?: 0)
        r = 31 * r + (seriesName?.hashCode() ?: 0)
        r = 31 * r + genres.hashCode()
        r = 31 * r + (coverBytes?.contentHashCode() ?: 0)
        r = 31 * r + (coverExtension?.hashCode() ?: 0)
        return r
    }

    companion object {
        val EMPTY = EpubMetadata(
            title = null, author = null, language = null, publisher = null,
            publishedYear = null, description = null, isbn = null, asin = null,
            seriesName = null, genres = emptyList(), coverBytes = null, coverExtension = null,
        )
    }
}

package com.riffle.core.domain.comic

/**
 * What we can extract from a CBZ at Add-to-Library time without opening a reader: the page count,
 * plus the bytes of the first image (used as the cover) and its extension. Title/author are not
 * derivable from a plain CBZ, so callers fall back to the filename.
 */
data class ComicMetadata(
    val pageCount: Int,
    val coverBytes: ByteArray?,
    val coverExtension: String?,
) {
    companion object {
        val EMPTY = ComicMetadata(pageCount = 0, coverBytes = null, coverExtension = null)
    }
}

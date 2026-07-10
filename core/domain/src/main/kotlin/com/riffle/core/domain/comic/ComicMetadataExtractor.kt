package com.riffle.core.domain.comic

import java.io.File

/**
 * Opens a CBZ and extracts (page count, first-image cover). Convention: the first entry in the
 * sorted image list IS the cover (Q13 of ADR 0042). Returns [ComicMetadata.EMPTY] on any I/O or
 * archive-format failure — the caller falls back to the filename.
 */
object ComicMetadataExtractor {

    fun extract(file: File): ComicMetadata = try {
        CbzArchive(file).use { archive ->
            val count = archive.pageCount
            if (count == 0) return ComicMetadata.EMPTY
            val coverBytes = archive.imageBytes(0)
            val coverExtension = extensionFor(archive.mediaType(0))
            ComicMetadata(
                pageCount = count,
                coverBytes = coverBytes,
                coverExtension = coverExtension,
            )
        }
    } catch (_: Exception) {
        ComicMetadata.EMPTY
    }

    private fun extensionFor(mediaType: String): String = when (mediaType) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        else -> "jpg"
    }
}

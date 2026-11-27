package com.riffle.core.data.localfiles

import com.riffle.core.models.EpubMetadata

data class LocalPdfMetadata(val title: String?, val author: String?, val subject: String?)

data class LocalCbzMetadata(val coverBytes: ByteArray?, val coverExtension: String?, val pageCount: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalCbzMetadata) return false
        return pageCount == other.pageCount &&
            coverExtension == other.coverExtension &&
            (coverBytes?.contentEquals(other.coverBytes) ?: (other.coverBytes == null))
    }

    override fun hashCode(): Int {
        var r = coverBytes?.contentHashCode() ?: 0
        r = 31 * r + (coverExtension?.hashCode() ?: 0)
        r = 31 * r + pageCount
        return r
    }
}

// Reads book metadata from a copied-in file; platform backends wrap the JVM/iOS extractors into the shared types.
interface LocalBookMetadataReader {
    suspend fun epub(copiedPath: String): EpubMetadata
    suspend fun pdf(copiedPath: String): LocalPdfMetadata
    suspend fun cbz(copiedPath: String): LocalCbzMetadata
}

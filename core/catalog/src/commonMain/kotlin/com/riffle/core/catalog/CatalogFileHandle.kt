package com.riffle.core.catalog

/**
 * An openable handle for a Catalog item's file. [Stream] is a remote URL (with any auth headers
 * baked in); [Local] is an already-materialised file path (LocalFiles Sources, or a hit against a
 * Source's Cache/Download tier).
 */
sealed class CatalogFileHandle {
    abstract val format: BookFormat
    abstract val sizeBytes: Long?

    data class Stream(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        override val format: BookFormat,
        override val sizeBytes: Long? = null,
    ) : CatalogFileHandle()

    data class Local(
        val path: String,
        override val format: BookFormat,
        override val sizeBytes: Long? = null,
    ) : CatalogFileHandle()
}

package com.riffle.core.catalog

/**
 * Opt-in capability for catalogs that can serve individual CBZ page images
 * without requiring a full file download. Komga implements this via
 * GET /api/v1/books/{bookId}/pages/{pageNumber}.
 *
 * [pageIndex] is always 0-based; implementations map to whatever the server
 * expects (Komga uses 1-based page numbers).
 */
interface CbzPageStreamCapability : CatalogCapability {
    /**
     * Returns the raw image bytes for [pageIndex] (0-based).
     * Pass [maxWidth] to request a downscaled image (e.g. 300 for thumbnails);
     * null requests the full-resolution original.
     */
    suspend fun fetchCbzPageImage(itemId: String, pageIndex: Int, maxWidth: Int? = null): ByteArray
    /** Returns the total page count for the item. */
    suspend fun fetchCbzPageCount(itemId: String): Int
}

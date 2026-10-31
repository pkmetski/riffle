package com.riffle.core.catalog

/**
 * Source that can return the user's reading history — items they have opened and their
 * server-reported progress. Used by the O'Reilly home tab to populate "Continue Reading"
 * and "Completed" sections directly from the remote platform.
 */
interface ReadingHistoryCapability : CatalogCapability {
    /**
     * Fetches the most recently read items for the authenticated user.
     *
     * [limit] caps the number of items returned. Items are sorted by most-recently-read first.
     * Each [CatalogItem] carries [CatalogItem.readingProgress] set from the server-reported
     * fraction (0..1). Items with no progress are excluded by the implementation.
     */
    suspend fun fetchReadingHistory(limit: Int = 48): List<CatalogItem>
}

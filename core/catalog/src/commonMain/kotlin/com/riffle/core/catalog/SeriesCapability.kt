package com.riffle.core.catalog

/**
 * A Catalog that groups its items into named series.
 *
 * Contract for implementers:
 *
 * - Populate [CatalogSeriesEntry.sequence] whenever the source has a position value for the book
 *   (ABS's `sequence`, EPUB3 `group-position`, Calibre's `series_index`, etc.). Leave it null
 *   only when the source genuinely has no ordering info — do not synthesise one from list index.
 * - Return [listSeries] entries and [listItemsInSeries] results already sorted with
 *   [SeriesEntryOrdering]. Do not trust the upstream server's order (ABS has been observed to
 *   return books in whatever `groupBy` produced, which is not sequence order) and do not fall
 *   back to alphabetical title order — that puts "Book 10" before "Book 2".
 */
interface SeriesCapability : CatalogCapability {
    suspend fun listSeries(rootId: String): List<CatalogSeries>
    suspend fun listItemsInSeries(rootId: String, seriesId: String): List<CatalogItem>
}

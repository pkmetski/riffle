package com.riffle.core.catalog

data class CatalogImportMetadata(
    val title: String,
    val author: String,
    val series: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val publishedYear: String? = null,
    val genres: List<String> = emptyList(),
    val isbn: String? = null,
    val asin: String? = null,
    val coverUrl: String? = null,
    val seriesSequence: String? = null,
)

data class CatalogImportChapter(
    val id: Int,
    val startSec: Double,
    val endSec: Double,
    val title: String,
)

data class CatalogImportFile(
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
    /** Opens the source response only for the duration of [block]. */
    val withStream: suspend (suspend (CatalogFileStream) -> Unit) -> Unit,
)

enum class CatalogImportPhase {
    Preparing,
    Uploading,
    Uploaded,
    Reconciling,
    Finalizing,
}

data class CatalogImportProgress(
    val phase: CatalogImportPhase,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
)

data class CatalogImportRequest(
    val libraryId: String,
    val folderId: String? = null,
    val metadata: CatalogImportMetadata,
    /** Files are ordered as presented by the source UI (important for multi-track audiobooks). */
    val files: List<CatalogImportFile>,
    val chapters: List<CatalogImportChapter> = emptyList(),
    val readingProgress: Float? = null,
    val ebookLocation: String? = null,
    val audioDurationSec: Double = 0.0,
    val onProgress: (CatalogImportProgress) -> Unit = {},
)

sealed interface CatalogImportResult {
    data class Uploaded(
        val destinationItemId: String? = null,
        val warnings: List<String> = emptyList(),
    ) : CatalogImportResult
    data class Failed(val cause: Throwable) : CatalogImportResult
}

/**
 * Opt-in capability for a catalog that can accept a book-shaped upload.
 *
 * This is deliberately independent from progress synchronisation: a destination may accept files
 * without exposing progress, and the upload flow must still be available in that case.
 */
interface BookImportCapability : CatalogCapability {
    suspend fun importBook(request: CatalogImportRequest): CatalogImportResult
}

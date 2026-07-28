package com.riffle.core.domain

import java.io.File

data class PdfMetadata(
    val title: String?,
    val author: String?,
    val subject: String?,
    val keywords: List<String>,
) {
    companion object {
        val EMPTY = PdfMetadata(title = null, author = null, subject = null, keywords = emptyList())
    }
}

/**
 * PDF metadata surface — an interface so the scanner stays JVM-testable. Pdfium's document-meta
 * reader is Android-only; the real [com.riffle.core.data.localfiles.PdfiumPdfMetadataExtractor]
 * uses it, and JVM tests substitute [NoOpPdfMetadataExtractor].
 */
interface PdfMetadataExtractor {
    suspend fun extract(file: File): PdfMetadata
}

/** Always returns empty. Used in JVM tests and as a fallback if the Pdfium impl fails. */
object NoOpPdfMetadataExtractor : PdfMetadataExtractor {
    override suspend fun extract(file: File): PdfMetadata = PdfMetadata.EMPTY
}

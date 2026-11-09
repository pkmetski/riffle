package com.riffle.core.domain

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocumentAuthorAttribute
import platform.PDFKit.PDFDocumentKeywordsAttribute
import platform.PDFKit.PDFDocumentSubjectAttribute
import platform.PDFKit.PDFDocumentTitleAttribute

data class IosPdfMetadata(
    val title: String?,
    val author: String?,
    val subject: String?,
    val keywords: List<String>,
) {
    companion object {
        val EMPTY = IosPdfMetadata(title = null, author = null, subject = null, keywords = emptyList())
    }
}

@OptIn(ExperimentalForeignApi::class)
object IosPdfMetadataExtractor {

    fun extract(pdfPath: String): IosPdfMetadata = try {
        val url = NSURL.fileURLWithPath(pdfPath)
        val doc = platform.PDFKit.PDFDocument(url) ?: return IosPdfMetadata.EMPTY
        val attrs = doc.documentAttributes() ?: return IosPdfMetadata.EMPTY
        val title = (attrs[PDFDocumentTitleAttribute] as? String)?.trim()?.ifEmpty { null }
        val author = (attrs[PDFDocumentAuthorAttribute] as? String)?.trim()?.ifEmpty { null }
        val subject = (attrs[PDFDocumentSubjectAttribute] as? String)?.trim()?.ifEmpty { null }
        @Suppress("UNCHECKED_CAST")
        val keywords = when (val kw = attrs[PDFDocumentKeywordsAttribute]) {
            is String -> kw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            is List<*> -> (kw as List<*>).mapNotNull { (it as? String)?.trim()?.ifEmpty { null } }
            else -> emptyList()
        }
        IosPdfMetadata(title = title, author = author, subject = subject, keywords = keywords)
    } catch (_: Exception) {
        IosPdfMetadata.EMPTY
    }
}

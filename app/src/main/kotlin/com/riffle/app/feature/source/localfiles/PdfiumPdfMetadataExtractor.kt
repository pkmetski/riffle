package com.riffle.app.feature.source.localfiles

import android.content.Context
import android.os.ParcelFileDescriptor
import com.riffle.core.domain.PdfMetadata
import com.riffle.core.domain.PdfMetadataExtractor
import com.shockwave.pdfium.PdfiumCore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pdfium-backed [PdfMetadataExtractor]. Only implementation on Android — JVM tests inject
 * [com.riffle.core.domain.NoOpPdfMetadataExtractor] via the scanner constructor.
 */
@Singleton
class PdfiumPdfMetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) : PdfMetadataExtractor {

    override suspend fun extract(file: File): PdfMetadata = try {
        val core = PdfiumCore(context)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val doc = core.newDocument(pfd)
        try {
            val meta = core.getDocumentMeta(doc)
            PdfMetadata(
                title = (meta.title as String?)?.takeIf { it.isNotBlank() },
                author = (meta.author as String?)?.takeIf { it.isNotBlank() },
                subject = (meta.subject as String?)?.takeIf { it.isNotBlank() },
                keywords = (meta.keywords as String?)
                    ?.split(',', ';')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList(),
            )
        } finally {
            core.closeDocument(doc)
        }
    } catch (_: Exception) {
        PdfMetadata.EMPTY
    }
}

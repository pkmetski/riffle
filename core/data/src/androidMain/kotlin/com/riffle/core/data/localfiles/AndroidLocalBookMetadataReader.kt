package com.riffle.core.data.localfiles

import com.riffle.core.domain.EpubMetadataExtractor
import com.riffle.core.domain.PdfMetadata
import com.riffle.core.domain.PdfMetadataExtractor
import com.riffle.core.domain.comic.ComicMetadataExtractor
import com.riffle.core.models.EpubMetadata
import java.io.File

class AndroidLocalBookMetadataReader(
    private val pdfMetadata: PdfMetadataExtractor,
) : LocalBookMetadataReader {

    override suspend fun epub(copiedPath: String): EpubMetadata =
        EpubMetadataExtractor.extract(File(copiedPath))

    override suspend fun pdf(copiedPath: String): LocalPdfMetadata {
        val metadata = try {
            pdfMetadata.extract(File(copiedPath))
        } catch (_: Exception) {
            PdfMetadata.EMPTY
        }
        return LocalPdfMetadata(title = metadata.title, author = metadata.author, subject = metadata.subject)
    }

    override suspend fun cbz(copiedPath: String): LocalCbzMetadata {
        val metadata = ComicMetadataExtractor.extract(File(copiedPath))
        return LocalCbzMetadata(
            coverBytes = metadata.coverBytes,
            coverExtension = metadata.coverExtension,
            pageCount = metadata.pageCount,
        )
    }
}

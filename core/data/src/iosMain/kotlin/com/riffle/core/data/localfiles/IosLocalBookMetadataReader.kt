package com.riffle.core.data.localfiles

import com.riffle.core.domain.IosCbzMetadata
import com.riffle.core.domain.IosCbzMetadataExtractor
import com.riffle.core.domain.IosEpubMetadataExtractor
import com.riffle.core.domain.IosPdfMetadata
import com.riffle.core.domain.IosPdfMetadataExtractor
import com.riffle.core.models.EpubMetadata

class IosLocalBookMetadataReader : LocalBookMetadataReader {

    override suspend fun epub(copiedPath: String): EpubMetadata =
        IosEpubMetadataExtractor.extract(copiedPath)

    override suspend fun pdf(copiedPath: String): LocalPdfMetadata {
        val metadata = runCatching { IosPdfMetadataExtractor.extract(copiedPath) }.getOrElse { IosPdfMetadata.EMPTY }
        return LocalPdfMetadata(title = metadata.title, author = metadata.author, subject = metadata.subject)
    }

    override suspend fun cbz(copiedPath: String): LocalCbzMetadata {
        val metadata = runCatching { IosCbzMetadataExtractor.extract(copiedPath) }.getOrElse { IosCbzMetadata.EMPTY }
        return LocalCbzMetadata(
            coverBytes = metadata.coverBytes,
            coverExtension = metadata.coverExtension,
            pageCount = metadata.pageCount,
        )
    }
}

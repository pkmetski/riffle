package com.riffle.core.data

import com.riffle.core.data.di.AudiobookCacheDir
import com.riffle.core.data.di.CbzCacheDir
import com.riffle.core.data.di.EpubCacheDir
import com.riffle.core.data.di.PdfCacheDir
import com.riffle.core.domain.ContentCacheArtifact
import com.riffle.core.domain.ContentCacheArtifactKind
import com.riffle.core.domain.ContentCacheArtifactScanner
import com.riffle.core.domain.ContentCacheKey
import java.io.File
import javax.inject.Inject

class ContentCacheArtifactScannerImpl @Inject constructor(
    @EpubCacheDir private val epubCacheDir: File,
    @PdfCacheDir private val pdfCacheDir: File,
    @AudiobookCacheDir private val audiobookCacheDir: File,
    @CbzCacheDir private val cbzCacheDir: File,
) : ContentCacheArtifactScanner {
    override fun listArtifacts(): List<ContentCacheArtifact> =
        fileArtifacts(epubCacheDir, ".epub", ContentCacheArtifactKind.Epub) +
            fileArtifacts(pdfCacheDir, ".pdf", ContentCacheArtifactKind.Pdf) +
            audiobookArtifacts(audiobookCacheDir) +
            fileArtifacts(cbzCacheDir, ".cbz", ContentCacheArtifactKind.Cbz)

    private fun fileArtifacts(root: File, extension: String, kind: ContentCacheArtifactKind): List<ContentCacheArtifact> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { sourceDir ->
                val prefix = sourceDir.absolutePath + File.separator
                sourceDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(extension) }
                    .map { file ->
                        val relative = file.absolutePath.removePrefix(prefix)
                        ContentCacheArtifact(
                            key = ContentCacheKey(
                                sourceId = sourceDir.name,
                                itemId = relative.removeSuffix(extension),
                                kind = kind,
                            ),
                            file = file,
                            sizeBytes = file.length(),
                            evidenceLastModifiedAtMs = file.lastModified().takeIf { it > 0L },
                        )
                    }
                    .toList()
            }
            ?: emptyList()

    private fun audiobookArtifacts(root: File): List<ContentCacheArtifact> =
        root.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { sourceDir ->
                val prefix = sourceDir.absolutePath + File.separator
                sourceDir.walkTopDown()
                    .filter { it.isFile && it.name == "manifest.json" }
                    .mapNotNull { manifest ->
                        val itemDir = manifest.parentFile ?: return@mapNotNull null
                        val relative = itemDir.absolutePath.removePrefix(prefix).takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        ContentCacheArtifact(
                            key = ContentCacheKey(
                                sourceId = sourceDir.name,
                                itemId = relative,
                                kind = ContentCacheArtifactKind.Audiobook,
                            ),
                            file = itemDir,
                            sizeBytes = itemDir.sizeBytes(),
                            evidenceLastModifiedAtMs = itemDir.newestLastModifiedAtMs(),
                        )
                    }
                    .toList()
            }
            ?: emptyList()
}

private fun File.sizeBytes(): Long =
    if (isFile) length() else walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun File.newestLastModifiedAtMs(): Long? =
    if (isFile) {
        lastModified().takeIf { it > 0L }
    } else {
        walkTopDown().map { it.lastModified() }.filter { it > 0L }.maxOrNull()
    }

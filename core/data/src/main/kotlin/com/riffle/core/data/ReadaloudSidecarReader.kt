package com.riffle.core.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Produces the Readaloud **sidecar** (ADR 0028): the `/synced` bundle with every audio resource
 * removed, repackaged as a small ZIP. The result is a valid audio-free EPUB, so [MediaOverlayReader]
 * and `EpubContentExtractor` consume it exactly as they consume the full bundle — the only
 * difference is ~1 MB instead of hundreds.
 *
 * Audio is identified and skipped by extension, so its byte ranges are never fetched through the
 * [RangeReader].
 */
object ReadaloudSidecarReader {

    private val AUDIO_EXTENSION = Regex("""\.(mp3|mp4|m4a|m4b|aac|ogg|opus)$""", RegexOption.IGNORE_CASE)

    fun read(totalSize: Long, reader: RangeReader): ByteArray {
        val extractor = ZipRangeExtractor(totalSize, reader)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for (entry in extractor.entries()) {
                if (entry.name.endsWith("/")) continue
                if (AUDIO_EXTENSION.containsMatchIn(entry.name)) continue
                zos.putNextEntry(ZipEntry(entry.name))
                zos.write(extractor.extract(entry))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

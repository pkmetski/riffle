package com.riffle.core.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Produces the Readaloud **sidecar** (ADR 0028): the `/synced` bundle with every audio resource
 * removed, repackaged as a small ZIP. The result is a valid audio-free EPUB, so [MediaOverlayReader]
 * and `EpubContentExtractor` consume it exactly as they consume the full bundle — the only
 * difference is ~1 MB instead of hundreds.
 *
 * Audio is identified and skipped by extension, so its bytes are never copied into the sidecar.
 */
object ReadaloudSidecarReader {

    private val AUDIO_EXTENSION = Regex("""\.(mp3|mp4|m4a|m4b|aac|ogg|opus)$""", RegexOption.IGNORE_CASE)

    /**
     * Streams the `/synced` bundle and copies the non-audio entries into the sidecar, STOPPING at the
     * first audio entry (ADR 0028). Returns null when no SMIL entry was seen before that stop point —
     * this happens either because the book is unaligned (no SMIL anywhere) or because this particular
     * bundle packs SMIL after audio in the zip. The caller cannot distinguish these cases from a stream
     * alone; use [readFromFile] on the fully-downloaded bundle to resolve the ambiguity.
     *
     * Storyteller writes audio entries as STORED with a data descriptor (general-purpose bit 3), which
     * ZipInputStream refuses — the moment it reads the first audio entry's local header it throws
     * ZipException. Non-audio entries are DEFLATED and (usually) packed before audio, so by the time
     * this fires the sidecar is already complete; treat it as end-of-prefix. Some bundles break this
     * ordering assumption — see [readFromFile] for the fallback.
     */
    fun readStreaming(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var hasSMIL = false
        ZipInputStream(input).use { zis ->
            ZipOutputStream(out).use { zos ->
                try {
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (AUDIO_EXTENSION.containsMatchIn(entry.name)) break
                        if (!entry.name.endsWith("/")) {
                            if (entry.name.endsWith(".smil", ignoreCase = true)) hasSMIL = true
                            zos.putNextEntry(ZipEntry(entry.name))
                            zis.copyTo(zos)
                            zos.closeEntry()
                        }
                        entry = zis.nextEntry
                    }
                } catch (e: ZipException) { /* treat as end of prefix */ }
            }
        }
        return if (hasSMIL) out.toByteArray() else null
    }

    /**
     * Extracts the sidecar from a fully-downloaded bundle on disk using [ZipFile] random access via
     * the central directory. Works regardless of entry ordering — handles bundles where SMIL appears
     * after audio (which [readStreaming] cannot reach). Returns null when the bundle has no SMIL at all
     * (Storyteller not yet aligned for this book).
     */
    fun readFromFile(zipFile: File): ByteArray? {
        val out = ByteArrayOutputStream()
        var hasSMIL = false
        ZipFile(zipFile).use { zf ->
            ZipOutputStream(out).use { zos ->
                zf.entries().asSequence()
                    .filter { !it.isDirectory && !AUDIO_EXTENSION.containsMatchIn(it.name) }
                    .forEach { entry ->
                        if (entry.name.endsWith(".smil", ignoreCase = true)) hasSMIL = true
                        zos.putNextEntry(ZipEntry(entry.name))
                        zf.getInputStream(entry).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
        }
        return if (hasSMIL) out.toByteArray() else null
    }
}

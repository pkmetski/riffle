package com.riffle.core.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
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
     * first audio entry (ADR 0028). Storyteller's aligned EPUB packs the text + Media Overlay `.smil`
     * files FIRST and the (huge) audio blobs LAST, so the whole sidecar is a ~1 MB front prefix: once an
     * audio entry appears, everything after it is audio. Stopping there closes the HTTP stream after the
     * prefix, so the prepare transfers ~1 MB and finishes shortly after the server's bundle-generation —
     * far faster than the full download (which streams the whole hundreds-of-MB bundle), even though both
     * wait out the same generation. Reading the whole stream instead would make the prepare as slow as a
     * full download (and time out), which is exactly the bug this avoids.
     *
     * Byte-range extraction (fetch only the non-audio bytes) isn't an option here: this Storyteller
     * regenerates the bundle per request and rejects higher-offset ranges with HTTP 416.
     *
     * [input] is read sequentially via local file headers — no central directory needed.
     */
    fun readStreaming(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        ZipInputStream(input).use { zis ->
            ZipOutputStream(out).use { zos ->
                try {
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (AUDIO_EXTENSION.containsMatchIn(entry.name)) break
                        if (!entry.name.endsWith("/")) {
                            zos.putNextEntry(ZipEntry(entry.name))
                            zis.copyTo(zos)
                            zos.closeEntry()
                        }
                        entry = zis.nextEntry
                    }
                } catch (e: ZipException) {
                    // Storyteller writes the audio entries STORED with a data descriptor (general-purpose
                    // bit 3), which java's ZipInputStream refuses — "only DEFLATED entries can have EXT
                    // descriptor" — the moment it reads the FIRST audio entry's local header. The non-audio
                    // entries (text + SMIL) are DEFLATED and packed BEFORE the audio, so by the time this
                    // fires we've already copied the whole sidecar; treat it as the end of the prefix rather
                    // than discarding everything. (This is also why streaming with ZipInputStream is the only
                    // viable reader: /synced rejects byte-ranges with HTTP 416, and a full ZipFile read would
                    // need the entire hundreds-of-MB bundle on disk first.)
                }
            }
        }
        return out.toByteArray()
    }
}

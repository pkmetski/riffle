package com.riffle.core.data

import com.riffle.core.domain.IosZipArchive
import com.riffle.core.domain.IosZipWriter

/**
 * iOS port of [ReadaloudSidecarReader] (ADR 0040): the `/synced` bundle with every audio resource
 * removed, repackaged as a small ZIP. The result is a valid audio-free EPUB, so the Media Overlay
 * reader consumes it exactly as it consumes the full bundle — the difference is ~1 MB instead of
 * hundreds.
 *
 * Android has two paths: a streaming fast path that stops at the first audio entry, and a
 * full-download fallback with random access. Kotlin/Native's zip reader needs the central
 * directory, which lives at the end of the archive, so iOS always takes the equivalent of the
 * fallback: read the whole bundle, then keep the non-audio entries. Same output, same
 * "no SMIL means not aligned" contract; only the bandwidth optimisation is absent.
 */
object IosReadaloudSidecarReader {

    private val AUDIO_EXTENSION = Regex("""\.(mp3|mp4|m4a|m4b|aac|ogg|opus)$""", RegexOption.IGNORE_CASE)

    /**
     * Returns the sidecar bytes, or null when the bundle carries no `.smil` entry at all — the book
     * is not yet aligned by Storyteller and there is nothing to cache.
     */
    fun read(bundleBytes: ByteArray): ByteArray? {
        val archive = IosZipArchive(bundleBytes)
        val names = archive.entryNames().filterNot { it.endsWith("/") }
        if (names.none { it.endsWith(".smil", ignoreCase = true) }) return null

        val kept = names
            .filterNot { AUDIO_EXTENSION.containsMatchIn(it) }
            .mapNotNull { name -> archive.readEntry(name)?.let { name to it } }
        if (kept.isEmpty()) return null

        return IosZipWriter.write(kept)
    }
}

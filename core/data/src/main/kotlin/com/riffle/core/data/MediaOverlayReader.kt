package com.riffle.core.data

import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.SmilOverlayParser
import com.riffle.core.domain.resolveEpubHref
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Reads the Media Overlay timeline out of a Storyteller synced EPUB bundle (ADR 0023).
 *
 * Every `.smil` entry is parsed with the pure [SmilOverlayParser]; each clip's relative `text`/
 * `audio` refs are resolved against the `.smil` entry's own folder so they become full
 * zip-internal paths the player and highlighter can use directly. SMIL entries are visited in
 * name order — Storyteller names them sequentially (`…part0001`, `…part0002`, …), which matches
 * spine/playback order without having to parse the OPF.
 */
object MediaOverlayReader {

    fun readTrack(epub: File): ReadaloudTrack {
        ZipFile(epub).use { zip ->
            val smilEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".smil", ignoreCase = true) }
                .sortedBy { it.name }
                .toList()

            val clips = ArrayList<MediaOverlayClip>()
            for (entry in smilEntries) {
                val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val base = entry.name.substringBeforeLast('/', missingDelimiterValue = "")
                SmilOverlayParser.parse(xml).forEach { clip ->
                    clips += clip.copy(
                        textFragmentRef = resolveEpubHref(clip.textFragmentRef, base),
                        audioSrc = resolveEpubHref(clip.audioSrc, base),
                    )
                }
            }
            return ReadaloudTrack(clips)
        }
    }

    /** Opens a stream for an audio resource named by its full zip-internal path. */
    fun openAudio(epub: File, audioPath: String): InputStream? {
        val zip = ZipFile(epub)
        val entry = zip.getEntry(audioPath) ?: run { zip.close(); return null }
        // The caller closes the returned stream; closing it closes the ZipFile too.
        return ClosingInputStream(zip.getInputStream(entry), zip)
    }

    private class ClosingInputStream(
        private val delegate: InputStream,
        private val zip: ZipFile,
    ) : InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() {
            delegate.close()
            zip.close()
        }
    }
}

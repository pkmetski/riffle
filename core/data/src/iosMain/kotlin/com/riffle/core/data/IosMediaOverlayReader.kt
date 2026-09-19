package com.riffle.core.data

import com.riffle.core.domain.IosSmilOverlayParser
import com.riffle.core.domain.IosZipArchive
import com.riffle.core.domain.MediaOverlayClip
import com.riffle.core.domain.ReadaloudTrack
import com.riffle.core.domain.resolveEpubHref

/**
 * iOS port of [MediaOverlayReader] (ADR 0027): reads the Media Overlay timeline out of a
 * Storyteller synced EPUB bundle.
 *
 * Identical algorithm to Android's — every `.smil` entry is parsed, visited in name order
 * (Storyteller names them `…part0001`, `…part0002`, …, which matches spine/playback order without
 * parsing the OPF), and each clip's relative `text`/`audio` ref is resolved against the `.smil`
 * entry's own folder via the shared [resolveEpubHref]. Only the zip and XML layers differ:
 * [IosZipArchive] instead of `java.util.zip.ZipFile`, and ksoup instead of `DocumentBuilderFactory`.
 */
object IosMediaOverlayReader {

    fun readTrack(bundleBytes: ByteArray): ReadaloudTrack {
        val archive = IosZipArchive(bundleBytes)
        val smilEntries = archive.entryNames()
            .filter { !it.endsWith("/") && it.endsWith(".smil", ignoreCase = true) }
            .sorted()

        val clips = mutableListOf<MediaOverlayClip>()
        for (name in smilEntries) {
            val xml = archive.readEntryAsText(name) ?: continue
            val base = name.substringBeforeLast('/', missingDelimiterValue = "")
            IosSmilOverlayParser.parse(xml).forEach { clip ->
                clips += clip.copy(
                    textFragmentRef = resolveEpubHref(clip.textFragmentRef, base),
                    audioSrc = resolveEpubHref(clip.audioSrc, base),
                )
            }
        }
        return ReadaloudTrack(clips)
    }

    /** Raw bytes of an audio resource named by its full zip-internal path, or null if absent. */
    fun readAudio(bundleBytes: ByteArray, audioPath: String): ByteArray? =
        IosZipArchive(bundleBytes).readEntry(audioPath)
}

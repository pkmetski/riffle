package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.EpubAnalyzer
import com.riffle.core.domain.ExtractedEpub
import com.riffle.core.domain.IosEpubChecksum
import com.riffle.core.domain.IosEpubContentExtractor
import com.riffle.core.domain.LocalEpubLocator

/**
 * [LocalEpubLocator] over the iOS EPUB namespaces, preferring a downloaded copy over a cached one
 * and falling back to the prepared readaloud sidecar for the Storyteller side (ADR 0040).
 */
class IosLocalEpubLocator(
    private val fileStore: FileStore,
    private val sidecarStore: IosReadaloudSidecarStore,
) : LocalEpubLocator {

    override fun localEpubPath(sourceId: String, itemId: String): String? {
        val download = fileStore.resolve(NS_EPUB_DOWNLOADS, "$sourceId/$itemId.epub")
        if (IosAudiobookFiles.exists(download)) return download
        val cache = fileStore.resolve(NS_EPUB_CACHE, "$sourceId/$itemId.epub")
        return if (IosAudiobookFiles.exists(cache)) cache else null
    }

    override fun sidecarEpubPath(storytellerSourceId: String, storytellerBookId: String): String? =
        sidecarStore.cachedSidecarPath(storytellerSourceId, storytellerBookId)
}

/**
 * [EpubAnalyzer] for iOS. Reads the file once and hands the bytes to both the checksum and the
 * extractor — Kotlin/Native's zip reader needs the central directory anyway, so unlike the JVM
 * side there is nothing to stream.
 */
object IosEpubAnalyzer : EpubAnalyzer {
    override fun checksum(path: String): String? =
        IosAudiobookFiles.readBytes(path)?.let { IosEpubChecksum.of(it) }

    override fun extract(path: String): ExtractedEpub? =
        IosAudiobookFiles.readBytes(path)?.let { IosEpubContentExtractor.extract(it) }
}

package com.riffle.shared.library

import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.feature.library.ReadaloudOfflineDownloader

/**
 * iOS [ReadaloudOfflineDownloader] (ADR 0040). Makes a *streaming-eligible* readaloud available
 * offline by eager-fetching the ABS audio its narration streams from, rather than pulling the
 * whole Storyteller bundle.
 *
 * Contract is Android's, exactly: returns true/false on success/failure, and **null** when the
 * book is not streaming-eligible — the caller
 * ([com.riffle.feature.library.LibraryItemDetailViewModel]) then falls back to the full-bundle
 * download through ReadaloudAudioRepository.
 *
 * "Streaming-eligible" here means the Storyteller book is linked to an ABS item that actually has
 * audio; that ABS audiobook is what the streaming path would play, so downloading it is what makes
 * the readaloud usable offline. Android decides the same thing by asking its streaming session
 * factory to build a session, which is Media3-shaped and has no iOS counterpart.
 */
internal class IosReadaloudOfflineDownloader(
    private val readaloudLinkRepository: ReadaloudLinkRepository,
    private val libraryItemDao: LibraryItemDao,
    private val audiobookDownloadRepository: AudiobookDownloadRepository,
) : ReadaloudOfflineDownloader {

    override suspend fun download(
        storytellerSourceId: String,
        storytellerBookId: String,
        onProgress: (Float) -> Unit,
    ): Boolean? {
        val links = runCatching {
            readaloudLinkRepository.findByStorytellerBook(storytellerSourceId, storytellerBookId)
        }.getOrNull().orEmpty()

        // A readaloud can link to several ABS rows (an ebook and an audiobook stub in different
        // libraries); only the one carrying audio can serve the streaming path.
        val audioLink = links.firstOrNull { link ->
            runCatching { libraryItemDao.getById(link.absSourceId, link.absLibraryItemId) }
                .getOrNull()
                ?.hasAudio == true
        } ?: return null

        if (audiobookDownloadRepository.isDownloaded(audioLink.absSourceId, audioLink.absLibraryItemId)) {
            onProgress(1f)
            return true
        }

        val result = audiobookDownloadRepository.download(
            audioLink.absSourceId,
            audioLink.absLibraryItemId,
        ) { downloaded, total ->
            if (total > 0L) onProgress((downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f))
        }
        return result is AudiobookDownloadResult.Success
    }
}

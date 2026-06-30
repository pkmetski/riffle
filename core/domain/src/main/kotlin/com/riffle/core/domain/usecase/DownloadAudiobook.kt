package com.riffle.core.domain.usecase

import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import javax.inject.Inject

/**
 * Permanently download an audiobook (every ABS track + manifest) so it plays offline (ADR 0029).
 * Thin use-case wrapper so the audiobook player ViewModel depends on a named action rather than
 * the repository directly. [onProgress] is invoked on every byte chunk written.
 */
open class DownloadAudiobook @Inject constructor(
    private val downloadRepository: AudiobookDownloadRepository,
) {
    open suspend operator fun invoke(
        serverId: String,
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudiobookDownloadResult = downloadRepository.download(serverId, itemId, onProgress)
}

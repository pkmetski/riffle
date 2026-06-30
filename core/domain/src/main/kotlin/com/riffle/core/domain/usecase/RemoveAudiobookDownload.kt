package com.riffle.core.domain.usecase

import com.riffle.core.domain.AudiobookDownloadRepository
import javax.inject.Inject

/** Remove the downloaded copy of an audiobook from disk. Returns bytes freed. */
open class RemoveAudiobookDownload @Inject constructor(
    private val downloadRepository: AudiobookDownloadRepository,
) {
    open suspend operator fun invoke(serverId: String, itemId: String): Long =
        downloadRepository.remove(serverId, itemId)
}

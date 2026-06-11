package com.riffle.app.feature.library

import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookSession

/** Test double: nothing is ever downloaded. */
internal object NoopAudiobookDownloadRepository : AudiobookDownloadRepository {
    override fun isDownloaded(serverId: String, itemId: String): Boolean = false
    override fun localSession(serverId: String, itemId: String): AudiobookSession? = null
    override suspend fun download(
        serverId: String,
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudiobookDownloadResult = AudiobookDownloadResult.Success
    override suspend fun remove(serverId: String, itemId: String): Long = 0L
}

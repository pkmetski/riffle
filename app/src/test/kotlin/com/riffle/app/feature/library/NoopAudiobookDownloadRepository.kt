package com.riffle.app.feature.library

import com.riffle.core.domain.AudiobookDownloadResult
import com.riffle.core.domain.AudiobookSession
import com.riffle.core.domain.JvmAudiobookDownloadRepository

/** Test double: nothing is ever downloaded. */
internal object NoopAudiobookDownloadRepository : JvmAudiobookDownloadRepository {
    override fun isDownloaded(sourceId: String, itemId: String): Boolean = false
    override fun localSession(sourceId: String, itemId: String): AudiobookSession? = null
    override suspend fun download(
        sourceId: String,
        itemId: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): AudiobookDownloadResult = AudiobookDownloadResult.Success
    override suspend fun remove(sourceId: String, itemId: String): Long = 0L
}

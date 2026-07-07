package com.riffle.app.feature.library

import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudTrack
import java.io.File

internal object NoopReadaloudAudioRepository : ReadaloudAudioRepository {
    override fun isAudioAvailable(sourceId: String, itemId: String): Boolean = false
    override fun bundleFile(sourceId: String, itemId: String): File? = null
    override suspend fun readTrack(sourceId: String, itemId: String): ReadaloudTrack? = null
    override suspend fun probeSizeBytes(sourceId: String, itemId: String): Long? = null
    override suspend fun downloadAudio(sourceId: String, bookId: String, onProgress: (Long, Long) -> Unit): AudioDownloadResult =
        AudioDownloadResult.Success
    override suspend fun removeAudio(sourceId: String, itemId: String): Long = 0L
}

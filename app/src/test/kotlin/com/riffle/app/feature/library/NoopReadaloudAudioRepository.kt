package com.riffle.app.feature.library

import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudTrack
import java.io.File

internal object NoopReadaloudAudioRepository : ReadaloudAudioRepository {
    override fun isAudioAvailable(itemId: String): Boolean = false
    override fun bundleFile(itemId: String): File? = null
    override suspend fun readTrack(itemId: String): ReadaloudTrack? = null
    override suspend fun probeSizeBytes(itemId: String): Long? = null
    override suspend fun downloadAudio(itemId: String, onProgress: (Long, Long) -> Unit): AudioDownloadResult =
        AudioDownloadResult.Success
    override suspend fun downloadAudio(bookId: String, serverId: String, onProgress: (Long, Long) -> Unit): AudioDownloadResult =
        AudioDownloadResult.Success
    override suspend fun removeAudio(itemId: String): Long = 0L
}

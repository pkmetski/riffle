package com.riffle.app.feature.library

import com.riffle.core.domain.AudioDownloadResult
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudTrack
import java.io.File

internal object NoopReadaloudAudioRepository : ReadaloudAudioRepository {
    override fun isAudioAvailable(serverId: String, itemId: String): Boolean = false
    override fun bundleFile(serverId: String, itemId: String): File? = null
    override suspend fun readTrack(serverId: String, itemId: String): ReadaloudTrack? = null
    override suspend fun probeSizeBytes(serverId: String, itemId: String): Long? = null
    override suspend fun downloadAudio(serverId: String, bookId: String, onProgress: (Long, Long) -> Unit): AudioDownloadResult =
        AudioDownloadResult.Success
    override suspend fun removeAudio(serverId: String, itemId: String): Long = 0L
}

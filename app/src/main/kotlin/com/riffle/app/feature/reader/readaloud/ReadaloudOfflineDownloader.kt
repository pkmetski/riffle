package com.riffle.app.feature.reader.readaloud

import android.content.Context
import com.riffle.core.domain.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Makes a streaming-eligible Readaloud available offline by eager-fetching its ABS audio (ADR 0028).
 * A narrow seam over [ReadaloudStreamingSessionFactory] + [StreamingAudioDownloader] so the
 * book-details ViewModel stays unit-testable.
 */
interface ReadaloudOfflineDownloader {
    /**
     * Eager-downloads the book's streamed audio for offline. Returns true/false on success/failure,
     * or **null** when the book isn't streaming-eligible (the caller falls back to the bundle download).
     */
    suspend fun download(
        storytellerServerId: String,
        storytellerBookId: String,
        onProgress: (Float) -> Unit,
    ): Boolean?
}

class ReadaloudOfflineDownloaderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val factory: ReadaloudStreamingSessionFactory,
    private val dispatchers: DispatcherProvider,
) : ReadaloudOfflineDownloader {
    override suspend fun download(
        storytellerServerId: String,
        storytellerBookId: String,
        onProgress: (Float) -> Unit,
    ): Boolean? {
        val session = runCatching { factory.tryBuild(storytellerServerId, storytellerBookId) }.getOrNull()
            ?: return null
        return runCatching {
            StreamingAudioDownloader.download(
                context,
                session.streaming.itemsByMediaId.values.toList(),
                session.absToken,
                dispatchers.io,
                onProgress,
            )
        }.isSuccess
    }
}

package com.riffle.core.data

import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.RemoteProgress
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkGetProgressResult
import com.riffle.core.network.NetworkSyncSessionResult

/**
 * The ABS ebook media-progress record as one reconcilable target (ADR 0030). Position is the
 * `ebookLocation` CFI. The PATCH also needs the `ebookProgress` fraction (ABS's library % +
 * finished-detection); since the local store keeps only the CFI, the fraction is supplied by
 * [readingProgress] — wired by the caller to the locally-persisted `library_items.readingProgress`.
 */
class AbsEbookProgressRemote(
    private val api: AbsSessionApi,
    private val baseUrl: String,
    private val token: String,
    private val insecureAllowed: Boolean,
    private val itemId: String,
    private val readingProgress: suspend () -> Float,
) : ProgressRemote<String> {

    override suspend fun get(): RemoteProgress<String>? =
        when (val r = api.getProgress(baseUrl, itemId, token, insecureAllowed)) {
            is NetworkGetProgressResult.Success -> RemoteProgress(r.progress.ebookLocation, r.progress.lastUpdate)
            is NetworkGetProgressResult.NetworkError -> null
        }

    override suspend fun patch(position: String): Long? {
        val payload = NetworkEbookProgressPayload(ebookLocation = position, ebookProgress = readingProgress())
        return when (val r = api.syncEbookProgress(baseUrl, itemId, payload, token, insecureAllowed)) {
            is NetworkSyncSessionResult.Success -> r.lastUpdate
            is NetworkSyncSessionResult.NetworkError -> null
        }
    }
}

/**
 * The ABS audiobook media-progress record as one reconcilable target (ADR 0030). Position is the
 * book-absolute `currentTime` in seconds. The PATCH needs the track [duration]; the local store keeps
 * only seconds, so duration is supplied by the caller from `library_items.audioDurationSec`.
 */
class AbsAudioProgressRemote(
    private val api: AbsSessionApi,
    private val baseUrl: String,
    private val token: String,
    private val insecureAllowed: Boolean,
    private val itemId: String,
    private val duration: suspend () -> Double,
) : ProgressRemote<Double> {

    override suspend fun get(): RemoteProgress<Double>? =
        when (val r = api.getProgress(baseUrl, itemId, token, insecureAllowed)) {
            is NetworkGetProgressResult.Success -> RemoteProgress(r.progress.currentTime, r.progress.lastUpdate)
            is NetworkGetProgressResult.NetworkError -> null
        }

    override suspend fun patch(position: Double): Long? {
        val payload = NetworkAudiobookProgressPayload(currentTime = position, duration = duration())
        return when (val r = api.syncAudiobookProgress(baseUrl, itemId, payload, token, insecureAllowed)) {
            is NetworkSyncSessionResult.Success -> r.lastUpdate
            is NetworkSyncSessionResult.NetworkError -> null
        }
    }
}

package com.riffle.core.data

import com.riffle.core.domain.EbookCfiTranslator
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.RemoteProgress
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkResult

/**
 * The ABS ebook media-progress record as one reconcilable target (ADR 0030). Position is stored
 * locally as Readium Locator JSON but ABS uses the epub.js `epubcfi(...)` format; [translator]
 * converts between the two at this boundary (ADR 0013). When the cached EPUB isn't available the
 * translator is null: GET returns null (Offline — row left dirty) and PATCH is skipped (PushFailed
 * — row left dirty) so no corrupt value ever enters the local store or ABS.
 *
 * The PATCH also needs the `ebookProgress` fraction (ABS's library % + finished-detection); since
 * the local store keeps only the Locator JSON, the fraction is supplied by [readingProgress] —
 * wired by the caller to the locally-persisted `library_items.readingProgress`.
 */
class AbsEbookProgressRemote(
    private val api: AbsSessionApi,
    private val baseUrl: String,
    private val token: String,
    private val insecureAllowed: Boolean,
    private val itemId: String,
    private val translator: EbookCfiTranslator?,
    private val readingProgress: suspend () -> Float,
) : ProgressRemote<String> {

    override suspend fun get(): RemoteProgress<String>? {
        val t = translator ?: return null
        val r = api.getProgress(baseUrl, itemId, token, insecureAllowed)
        if (r !is NetworkResult.Success) return null
        val raw = r.value.ebookLocation
        // ABS returns blank when the book has never been opened; skip translation so the
        // reconciler can still compare timestamps and push local progress if it's newer.
        val locatorJson = if (raw.isBlank()) "" else t.cfiToLocatorJson(raw) ?: return null
        return RemoteProgress(locatorJson, r.value.lastUpdate)
    }

    override suspend fun patch(position: String): Long? {
        val t = translator ?: return null
        val cfi = t.locatorJsonToCfi(position) ?: return null
        val payload = NetworkEbookProgressPayload(ebookLocation = cfi, ebookProgress = readingProgress())
        val r = api.syncEbookProgress(baseUrl, itemId, payload, token, insecureAllowed)
        return (r as? NetworkResult.Success)?.value
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

    override suspend fun get(): RemoteProgress<Double>? {
        val r = api.getProgress(baseUrl, itemId, token, insecureAllowed)
        if (r !is NetworkResult.Success) return null
        return RemoteProgress(r.value.currentTime, r.value.lastUpdate)
    }

    override suspend fun patch(position: Double): Long? {
        val payload = NetworkAudiobookProgressPayload(currentTime = position, duration = duration())
        val r = api.syncAudiobookProgress(baseUrl, itemId, payload, token, insecureAllowed)
        return (r as? NetworkResult.Success)?.value
    }
}

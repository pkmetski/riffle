package com.riffle.core.data

import com.riffle.core.catalog.AudiobookProgressPeerCapability
import com.riffle.core.catalog.CfiDialect
import com.riffle.core.catalog.ProgressPeerCapability
import com.riffle.core.common.Clock
import com.riffle.core.domain.EbookCfiTranslator
import com.riffle.core.domain.ProgressRemote
import com.riffle.core.domain.RemoteProgress

/**
 * An ebook media-progress record as one reconcilable target (ADR 0030), routed through the
 * Source's [ProgressPeerCapability]. Position is stored locally as Readium Locator JSON. Whether
 * the peer stores that same JSON verbatim ([CfiDialect.READIUM_NATIVE]), a foreign epub.js
 * `epubcfi(...)` string ([CfiDialect.EPUB_JS]), or an opaque page-number
 * ([CfiDialect.PAGE_NUMBER]) is decided by [ProgressPeerCapability.cfiDialect]:
 *
 *  - [CfiDialect.EPUB_JS] (ABS today, ADR 0013): [translator] converts at the Catalog boundary.
 *    When the cached EPUB isn't available the translator is null — GET returns null (Offline, row
 *    left dirty) and PATCH is skipped (PushFailed, row left dirty) so no corrupt value ever
 *    enters either side.
 *  - [CfiDialect.READIUM_NATIVE]: the peer already speaks Locator JSON, so the translator MUST
 *    NOT run — even when supplied — and a null translator is normal, not "not cached yet".
 *  - [CfiDialect.PAGE_NUMBER]: the peer speaks an opaque numeric page (Komga's `page`, #528). The
 *    translator MUST NOT run; position bytes pass through verbatim.
 *
 * The PATCH also needs the `ebookProgress` fraction (ABS's library % + finished-detection); since
 * the local store keeps only the Locator JSON, the fraction is supplied by [readingProgress] —
 * wired by the caller to the locally-persisted `library_items.readingProgress`.
 */
class CatalogEbookProgressRemote(
    private val peer: ProgressPeerCapability,
    private val itemId: String,
    private val translator: EbookCfiTranslator?,
    private val readingProgress: suspend () -> Float,
    private val clock: Clock,
) : ProgressRemote<String> {

    override suspend fun get(): RemoteProgress<String>? {
        val r = runCatching { peer.pullProgress(itemId) }.getOrNull() ?: return null
        val raw = r.ebookLocation.orEmpty()
        val locatorJson = when (peer.cfiDialect) {
            CfiDialect.READIUM_NATIVE, CfiDialect.PAGE_NUMBER -> raw
            CfiDialect.EPUB_JS -> {
                val t = translator ?: return null
                // ABS returns blank when the book has never been opened; skip translation so the
                // reconciler can still compare timestamps and push local progress if it's newer.
                if (raw.isBlank()) "" else t.cfiToLocatorJson(raw) ?: return null
            }
        }
        return RemoteProgress(locatorJson, r.lastUpdate)
    }

    override suspend fun patch(position: String): Long? {
        val payload = when (peer.cfiDialect) {
            CfiDialect.READIUM_NATIVE, CfiDialect.PAGE_NUMBER -> position
            CfiDialect.EPUB_JS -> {
                val t = translator ?: return null
                t.locatorJsonToCfi(position) ?: return null
            }
        }
        return runCatching {
            val stamp = peer.pushEbookProgress(
                itemId = itemId,
                location = payload,
                progress = readingProgress(),
                isFinished = null,
                lastUpdateEpochMs = clock.nowMs(),
            )
            stamp?.takeIf { it > 0L } ?: clock.nowMs()
        }.getOrNull()
    }
}

/**
 * An audiobook media-progress record as one reconcilable target (ADR 0030), routed through the
 * Source's [AudiobookProgressPeerCapability]. Position is the book-absolute `currentTime` in
 * seconds. The PATCH needs the track [duration]; the local store keeps only seconds, so duration
 * is supplied by the caller from `library_items.audioDurationSec`. The same [peer] serves both
 * the audio push and the unified `pullProgress` since [AudiobookProgressPeerCapability] extends
 * [ProgressPeerCapability] (#528).
 */
class CatalogAudioProgressRemote(
    private val peer: AudiobookProgressPeerCapability,
    private val itemId: String,
    private val duration: suspend () -> Double,
    private val clock: Clock,
) : ProgressRemote<Double> {

    override suspend fun get(): RemoteProgress<Double>? {
        val r = runCatching { peer.pullProgress(itemId) }.getOrNull() ?: return null
        return RemoteProgress(r.audioCurrentTime, r.lastUpdate)
    }

    override suspend fun patch(position: Double): Long? =
        runCatching {
            val stamp = peer.pushAudiobookProgress(
                itemId = itemId,
                currentTimeSec = position,
                durationSec = duration(),
                isFinished = null,
                lastUpdateEpochMs = clock.nowMs(),
            )
            stamp?.takeIf { it > 0L } ?: clock.nowMs()
        }.getOrNull()
}

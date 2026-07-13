package com.riffle.core.catalog

/**
 * A peer that stores per-item **ebook** reading progress and can be synced against. Sources that
 * also serve audiobooks additionally implement [AudiobookProgressPeerCapability] — Komga and
 * similar comic/ebook-only sources don't.
 *
 * Push returns the source-side timestamp the write was stored under (mirrors ABS's PATCH
 * response) so callers can adopt it as `localUpdatedAt` — critical for last-update-wins
 * reconciliation (ADR 0008 / 0030). Returns `null` (or `0L`) if the source can't report a stamp;
 * callers substitute the client clock.
 */
interface ProgressPeerCapability : CatalogCapability {
    /**
     * Which position dialect this peer stores its ebook position in — see [CfiDialect]. Callers
     * on the ebook path gate translator invocation on this: [CfiDialect.EPUB_JS] round-trips
     * through the translator, [CfiDialect.READIUM_NATIVE] passes the local Locator JSON straight
     * through, and [CfiDialect.PAGE_NUMBER] passes an opaque page-number string with no
     * translation (Komga's `page` field).
     */
    val cfiDialect: CfiDialect get() = CfiDialect.EPUB_JS

    /**
     * [isFinished] is tri-state: `null` = leave the item-level finished flag untouched (this is
     * the common case — routine reader-position saves must not touch the audio dimension of a
     * peer's shared media-progress record); `true`/`false` = explicitly set/clear it (only
     * mark-finished / mark-unread callers pass a non-null value).
     */
    suspend fun pushEbookProgress(
        itemId: String,
        location: String,
        progress: Float,
        isFinished: Boolean?,
        lastUpdateEpochMs: Long,
    ): Long?

    suspend fun pullProgress(itemId: String): CatalogProgress?

    /** Bulk pull for reconciliation cycles. Empty when no items have progress on this peer. */
    suspend fun pullAllProgress(): List<CatalogProgress>
}

/**
 * A peer that **also** stores audiobook listening progress in addition to ebook progress. Split
 * from [ProgressPeerCapability] so ebook-only sources (Komga #528) don't have to stub an audio
 * push that will never run — the sweep gates audio work on `is AudiobookProgressPeerCapability`
 * and skips it for peers that lack it.
 *
 * Extends [ProgressPeerCapability]: every audio peer is by construction also a progress peer
 * (Storyteller's own record is not a peer, ADR 0029; ABS's audio side rides the same
 * `pullProgress` envelope as its ebook side). Codifying the relationship in the type system lets
 * downstream endpoint types carry a single peer reference instead of the two-slot construction
 * we had to write everywhere before (`peer = peer, audioPeer = peer`).
 */
interface AudiobookProgressPeerCapability : ProgressPeerCapability {
    suspend fun pushAudiobookProgress(
        itemId: String,
        currentTimeSec: Double,
        durationSec: Double,
        isFinished: Boolean?,
        lastUpdateEpochMs: Long,
    ): Long?
}

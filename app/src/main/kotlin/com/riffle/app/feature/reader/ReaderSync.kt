package com.riffle.app.feature.reader

import com.riffle.core.domain.BookSyncState
import com.riffle.core.domain.CanonicalReaderPosition
import com.riffle.core.domain.LocalCanonical
import com.riffle.core.domain.PositionTranslator
import com.riffle.core.domain.ProgressPeer
import com.riffle.core.domain.ProgressSyncStrategy
import com.riffle.core.domain.RemoteKind
import com.riffle.core.domain.RemoteRead
import com.riffle.core.domain.WriteResult
import com.riffle.core.network.AbsSessionApi
import com.riffle.core.network.NetworkAudiobookProgressPayload
import com.riffle.core.network.NetworkEbookProgressPayload
import com.riffle.core.network.NetworkResult

/**
 * Resolved ABS endpoint for a matched ABS Library Item (its media-progress record). [durationSec]
 * is the item's total audio length, sent with audiobook progress so ABS reports a real percentage;
 * 0 for the ebook endpoint.
 */
data class AbsSyncEndpoint(val baseUrl: String, val token: String, val insecure: Boolean, val itemId: String, val durationSec: Double = 0.0)

// A reachable peer that has no position yet: it never wins (timestamp 0) but is stale relative
// to any local progress, so the cycle still pushes the reader's first position to it.
internal val EMPTY_PEER_READ = RemoteRead(CanonicalReaderPosition(""), 0L)

// ABS's progress PATCH replies "OK" with no timestamp, so after a write we GET the record back to
// learn the server time it was stored under. Callers record it as the local timestamp; without it a
// write reads back next cycle as a "newer remote" and overwrites local progress (the feedback loop).
internal suspend fun AbsSessionApi.serverStamp(ep: AbsSyncEndpoint): Long? =
    (getProgress(ep.baseUrl, ep.itemId, ep.token, ep.insecure) as? NetworkResult.Success)?.value?.lastUpdate

// Write the matched audiobook's currentTime and read back the server timestamp it was stored under.
// One definition for both the cycle peer's outbound patch and the responsive follow-loop push, so the
// payload shape and the stamp read-back can never drift apart. `null` on failure.
internal suspend fun AbsSessionApi.writeAudiobookSeconds(ep: AbsSyncEndpoint, seconds: Double): Long? {
    val payload = NetworkAudiobookProgressPayload(seconds.coerceAtLeast(0.0), ep.durationSec)
    if (syncAudiobookProgress(ep.baseUrl, ep.itemId, payload, ep.token, ep.insecure) !is NetworkResult.Success) return null
    return serverStamp(ep)
}

/** ABS ebook progress as a [ProgressPeer]: an ebookLocation CFI on the ABS EPUB. */
internal class AbsEbookProgressPeer(
    private val api: AbsSessionApi,
    private val ep: AbsSyncEndpoint,
    private val translator: PositionTranslator,
) : ProgressPeer {
    override val id = RemoteKind.ABS_EBOOK.name

    override suspend fun tryGet(): RemoteRead? {
        // NetworkError → null (unreachable, excluded); 404/no-CFI → reachable-empty placeholder.
        val p = (api.getProgress(ep.baseUrl, ep.itemId, ep.token, ep.insecure) as? NetworkResult.Success)
            ?.value ?: return null
        val cfi = p.ebookLocation.takeIf { it.startsWith("epubcfi(") }
        if (p.lastUpdate <= 0L || cfi == null) return EMPTY_PEER_READ
        val canonical = translator.absCfiToCanonical(cfi) ?: return EMPTY_PEER_READ
        return RemoteRead(CanonicalReaderPosition(canonical), p.lastUpdate)
    }

    override suspend fun tryPatch(canonical: CanonicalReaderPosition): WriteResult {
        val cfi = translator.canonicalToAbsCfi(canonical.value)
            ?: return WriteResult.Skipped // canonical can't be placed in the ABS EPUB this cycle
        val payload = NetworkEbookProgressPayload(cfi, translator.canonicalBookProgress(canonical.value))
        if (api.syncEbookProgress(ep.baseUrl, ep.itemId, payload, ep.token, ep.insecure) !is NetworkResult.Success)
            return WriteResult.Failed("ABS ebook PATCH network error")
        val stamp = api.serverStamp(ep) ?: return WriteResult.Failed("ABS ebook stamp read-back failed")
        return WriteResult.Ok(stamp)
    }
}

/**
 * ABS audiobook progress as a sync remote, translated through the readaloud bundle's SMIL media
 * overlay — the exact page↔audio-timestamp mapping. [tryGet] turns the audiobook `currentTime` into a
 * reading position (so a newer listen on another device moves the reader); [tryPatch] turns the
 * winning reading position into an audiobook `currentTime` (so reading advances the audiobook). The
 * SMIL times are made absolute over the concatenated audio files in [PositionTranslator], so
 * `currentTime` matches the ABS audiobook's single-file timeline rather than a per-file offset.
 *
 * The raw audio *clock* is never written here — only positions derived from the canonical reading
 * position — so a behind/early playback clock cannot drive the ebook. The feedback loop is closed by
 * adopting the server timestamp each write returns (the cycle / push records it as the local time).
 */
internal class AbsAudiobookProgressPeer(
    private val api: AbsSessionApi,
    private val ep: AbsSyncEndpoint,
    private val translator: PositionTranslator,
) : ProgressPeer {
    override val id = RemoteKind.ABS_AUDIO.name

    override suspend fun tryGet(): RemoteRead? {
        val p = (api.getProgress(ep.baseUrl, ep.itemId, ep.token, ep.insecure) as? NetworkResult.Success)
            ?.value ?: return null
        if (p.lastUpdate <= 0L || p.currentTime <= 0.0) return EMPTY_PEER_READ // no audiobook position yet
        val canonical = translator.audioSecondsToCanonical(p.currentTime) ?: return EMPTY_PEER_READ
        return RemoteRead(CanonicalReaderPosition(canonical), p.lastUpdate)
    }

    override suspend fun tryPatch(canonical: CanonicalReaderPosition): WriteResult {
        val seconds = translator.canonicalToAudioSeconds(canonical.value)
            ?: return WriteResult.Skipped // canonical can't be placed on the audio timeline this cycle
        val stamp = api.writeAudiobookSeconds(ep, seconds)
            ?: return WriteResult.Failed("ABS audiobook PATCH or stamp read-back failed")
        return WriteResult.Ok(stamp)
    }
}

/**
 * Wraps a peer so the cycle still READS it (a genuinely-newer remote still wins inbound) but never
 * PATCHes it. Used while the reader is parked on the sentence readaloud stopped on: readaloud already
 * wrote the precise audiobook position on close, so the cycle's page-derived outbound push must not
 * regress it to the page top (ADR 0031). Outbound resumes once the user navigates off that page.
 */
private class InboundOnlyPeer(private val inner: ProgressPeer) : ProgressPeer {
    override val id = inner.id
    override suspend fun tryGet(): RemoteRead? = inner.tryGet()
    override suspend fun tryPatch(canonical: CanonicalReaderPosition): WriteResult = WriteResult.Skipped
}

/**
 * Drives one matched readaloud book's two-ABS-peer reconciliation (ADR 0019, as amended by ADR 0029
 * which dropped the Storyteller position peer). Builds the live
 * [ProgressPeer]s for the applicable peer set and runs the unified [ProgressSyncStrategy] each
 * cycle. The canonical position is the displayed-EPUB Locator JSON; [runCycle] returns the
 * Locator JSON the reader should jump to, or `null` for no jump.
 *
 * The ABS ebook and audiobook remotes target their own matched ABS Library Item — the same item
 * when it is a combined ebook+audiobook, or two separate items when a library splits ebooks and
 * audiobooks (ADR 0019). A peer that can't be built (missing endpoint) is simply skipped.
 */
/**
 * @param jumpLocatorJson the displayed-EPUB Locator JSON to jump the reader to, or `null` for
 *   no jump. @param canonicalLastUpdate the winning timestamp to persist as `localUpdatedAt`.
 */
data class ReaderSyncCycleResult(val jumpLocatorJson: String?, val canonicalLastUpdate: Long)

/**
 * Outcome of an audio-led reconciliation cycle (ADR 0029). [jumpToAudioSec] is the book-absolute
 * second the [Audiobook Player] should seek to when a genuinely-newer remote won, else `null`;
 * [canonicalLastUpdate] is the winning timestamp the caller adopts as `localUpdatedAt`.
 */
data class AudioLedCycleResult(val jumpToAudioSec: Double?, val canonicalLastUpdate: Long)

class ReaderSyncCoordinator(
    private val state: BookSyncState,
    private val translator: PositionTranslator,
    private val absApi: AbsSessionApi,
    // The ABS ebook and audiobook remotes target their own matched Library Item: one combined item
    // (same endpoint) or two separate items when a library splits ebooks and audiobooks (ADR 0019).
    private val absEbookEndpoint: AbsSyncEndpoint?,
    private val absAudioEndpoint: AbsSyncEndpoint?,
) {
    /**
     * @param pushAudio when `false`, the audiobook peer is reconciled **inbound-only** (read, never
     *   patched) — readaloud owns the precise audiobook write while the reader is parked on the
     *   sentence it stopped on, so the page-derived outbound can't regress it (ADR 0031).
     */
    suspend fun runCycle(displayedLocatorJson: String, localUpdatedAt: Long, pushAudio: Boolean = true): ReaderSyncCycleResult {
        val strategy = ProgressSyncStrategy { kind ->
            when (kind) {
                RemoteKind.ABS_EBOOK -> absEbookEndpoint?.let { AbsEbookProgressPeer(absApi, it, translator) }
                RemoteKind.ABS_AUDIO -> absAudioEndpoint?.let {
                    val peer = AbsAudiobookProgressPeer(absApi, it, translator)
                    if (pushAudio) peer else InboundOnlyPeer(peer)
                }
                // Bookmarks are not a position peer — they reconcile via the sweep, not this cycle.
                RemoteKind.ABS_BOOKMARK -> null
            }
        }
        val local = LocalCanonical(CanonicalReaderPosition(displayedLocatorJson), localUpdatedAt)
        val result = strategy.runCycle(state, local)
        // Guard the empty-remote placeholder: it never legitimately wins, but never jump to "".
        val jump = result.jumpTo?.value?.takeIf { it.isNotEmpty() }
        return ReaderSyncCycleResult(jump, result.canonicalLastUpdate)
    }

    /**
     * The same two-ABS-peer reconciliation, but **audio-led** for the [Audiobook Player] (ADR 0029):
     * the local position is the live listen position in book-absolute seconds rather than a displayed
     * EPUB locator. The seconds are translated to the canonical text position through the bundle's SMIL
     * (so a winning listen propagates to the ebook CFI), and any inbound winner is translated back to
     * seconds so the caller can seek the player. The bridge stays encapsulated — the audiobook player
     * never sees a Locator.
     *
     * Returns [AudioLedCycleResult.jumpToAudioSec] (non-null only when a genuinely-newer remote won, so
     * the player seeks there) and the winning timestamp to adopt as `localUpdatedAt`. When the audio
     * position can't be translated yet (prerequisites mid-cache), the cycle is skipped — local time
     * unchanged, no jump.
     */
    suspend fun runAudioLedCycle(currentAudioSec: Double, localUpdatedAt: Long): AudioLedCycleResult {
        val localCanonical = translator.audioSecondsToCanonical(currentAudioSec)
            ?: return AudioLedCycleResult(jumpToAudioSec = null, canonicalLastUpdate = localUpdatedAt)
        val result = runCycle(localCanonical, localUpdatedAt)
        val jumpAudio = result.jumpLocatorJson?.let { translator.canonicalToAudioSeconds(it) }
        return AudioLedCycleResult(jumpToAudioSec = jumpAudio, canonicalLastUpdate = result.canonicalLastUpdate)
    }

    /** The narrated fragment a canonical reading position falls in, so readaloud can start exactly
     *  where a server-sync jump placed the reader rather than at the page top. */
    fun fragmentForCanonical(canonicalLocatorJson: String): String? =
        translator.canonicalToFragmentRef(canonicalLocatorJson)

    /** Whether this matched book carries an audiobook ABS record (the dual-write sibling, ADR 0030). */
    val hasAudioTarget: Boolean get() = absAudioEndpoint != null

    /** The ABS item id of the audiobook record — the audio store key for the dual-write. May differ
     *  from the ebook item id when a library splits ebooks and audiobooks (ADR 0019). */
    val audioItemId: String? get() = absAudioEndpoint?.itemId

    /** The ABS item id of the ebook record — the reading store key for the dual-write (ADR 0030). */
    val ebookItemId: String? get() = absEbookEndpoint?.itemId

    /** The audiobook second a reading position maps to (bundle SMIL) — the value the cycle already
     *  pushes to ABS_AUDIO; the dual-write persists it locally too. `null` if untranslatable. */
    fun audioSecondsForCanonical(canonicalLocatorJson: String): Double? =
        translator.canonicalToAudioSeconds(canonicalLocatorJson)

    /** The audiobook second the **narrated sentence** maps to (bundle SMIL, sentence-precise), falling
     *  back to the page-derived position. Used to persist the local audiobook position on readaloud
     *  close/pause (ADR 0031). `null` if neither can be translated. */
    fun audioSecondsForFragment(fragmentRef: String, fallbackCanonicalJson: String?): Double? =
        translator.audioSecondsForFragment(fragmentRef)
            ?: fallbackCanonicalJson?.let { translator.canonicalToAudioSeconds(it) }

    /** The narrated sentence an absolute audio second falls in (bundle SMIL, index-free) — seeds the
     *  readaloud start from a local listen position (ADR 0031). */
    fun fragmentForAudioSeconds(seconds: Double): String? = translator.fragmentForAudioSeconds(seconds)

    /** The reading-position locator JSON an audiobook second maps to (bundle SMIL) — the counterpart
     *  for the audiobook player's dual-write onto the reading store. `null` if untranslatable. */
    fun canonicalForAudioSeconds(seconds: Double): String? =
        translator.audioSecondsToCanonical(seconds)

    /**
     * The readaloud resume anchor (reader href + column progression + narrated sentence) an audiobook
     * second maps to, via the bundle's SMIL (ADR 0031). Lets the audiobook player write the readaloud
     * resume so reopening the reader and starting readaloud lands where the listen got to. `null` when
     * the seconds can't be translated (prerequisites mid-cache).
     */
    fun readaloudAnchorForAudioSeconds(seconds: Double): com.riffle.core.domain.ReadaloudResumePosition? {
        val canonicalJson = translator.audioSecondsToCanonical(seconds) ?: return null
        val pos = CanonicalReaderPosition(canonicalJson)
        val href = pos.href ?: return null
        return com.riffle.core.domain.ReadaloudResumePosition(
            href = href,
            progression = pos.chapterProgression,
            fragmentRef = translator.canonicalToFragmentRef(canonicalJson),
        )
    }

    /**
     * Re-keys a "Play from here" selection ref (the displayed ABS `href#spanId`) onto the Storyteller
     * bundle chapter the selection sits in, so the player resolves the clip in THAT chapter rather than
     * the first book-wide occurrence of the span id (ids recur across chapters — the "Play-from-here
     * reset my progress" bug). Returns the bundle-href ref, or `null` when the chapter can't be mapped
     * or the ref carries no span id; the caller then plays the original ref unchanged.
     */
    fun bundleFragmentRefForSelection(displayedRef: String): String? {
        val spanId = displayedRef.substringAfter('#', "").takeIf { it.isNotEmpty() } ?: return null
        val bundleHref = translator.displayedHrefToBundleHref(displayedRef.substringBefore('#')) ?: return null
        return "$bundleHref#$spanId"
    }

    /**
     * Responsive update of the matched ABS audiobook's `currentTime` from the current **reading
     * position**, translated through the bundle's SMIL ([PositionTranslator.canonicalToAudioSeconds],
     * absolute over the concatenated audio files). Used by the audiobook-follow loop while readaloud
     * plays (the page tracks the audio) so the audiobook reaches the server between reconcile cycles.
     * Writes ONLY the audiobook item, from a page-derived position — never the raw audio clock — so it
     * can never erase or override reading progress.
     *
     * Returns the server's `lastUpdate` for the write, or `null` on no-op / failure. The caller must
     * record it as the local timestamp: the audiobook remote reads this same record back next cycle,
     * and only an equal-or-older read keeps the reading position the winner — this closes the feedback
     * loop without dropping inbound audiobook sync. ABS's PATCH carries no timestamp, so we GET it back.
     */
    suspend fun pushAudiobookProgress(canonicalLocatorJson: String): Long? =
        pushAudiobookAtSeconds(translator.canonicalToAudioSeconds(canonicalLocatorJson))

    /**
     * Responsive audiobook update from the **exact narrated sentence** (the player's active fragment),
     * not the page — so while readaloud plays, the audiobook `currentTime` matches the sentence the
     * user hears, instead of lagging to the top of the page. Falls back to the page-derived position
     * when the fragment can't be placed.
     */
    suspend fun pushAudiobookForFragment(fragmentRef: String, fallbackCanonicalJson: String?): Long? {
        val seconds = translator.audioSecondsForFragment(fragmentRef)
            ?: fallbackCanonicalJson?.let { translator.canonicalToAudioSeconds(it) }
        return pushAudiobookAtSeconds(seconds)
    }

    private suspend fun pushAudiobookAtSeconds(seconds: Double?): Long? {
        val ep = absAudioEndpoint ?: return null
        if (seconds == null) return null
        return absApi.writeAudiobookSeconds(ep, seconds)
    }
}

/**
 * Bundle-SMIL-only audiobook follow (ADR 0031): translates a narrated fragment to its absolute audio
 * second and pushes the matched ABS audiobook record — using **only** the Storyteller bundle's SMIL,
 * with no cross-EPUB index or ABS EPUB. This lets readaloud sync to the audiobook even when the full
 * [ReaderSyncCoordinator] can't be built (e.g. the cross-EPUB index isn't ready or a multi-link guard
 * trips). It also produces the **ebook** position for an audio second — a text-anchored locator built
 * from the bundle's own sentence text ([ebookItemId]/[sourceId] key the stores), so audiobook→ebook
 * sync also works index-free (ADR 0031: both directions go via the bundle, never the cross-EPUB index).
 */
class AudiobookFollow(
    private val absApi: AbsSessionApi,
    private val endpoint: AbsSyncEndpoint,
    private val translator: PositionTranslator,
    val sourceId: String,
    val audioItemId: String,
    val ebookItemId: String? = null,
    private val quotes: Map<String, com.riffle.core.domain.SentenceQuote> = emptyMap(),
) {
    /** The absolute audio second the narrated sentence begins (bundle SMIL), or `null` if unknown. */
    fun secondsForFragment(fragmentRef: String): Double? = translator.fragmentRefToAudioSeconds(fragmentRef)

    /** The narrated sentence an absolute audio second falls in (bundle SMIL, index-free) — seeds the
     *  readaloud start from a local listen position even with no cross-EPUB index (ADR 0031). */
    fun fragmentForAudioSeconds(seconds: Double): String? =
        translator.audioSecondsToStorytellerProgression(seconds)?.let { translator.fragmentAt(it) }

    /** The **ebook** reading position (a text-anchored Readium locator JSON) for an audio second —
     *  index-free: maps seconds → narrated sentence (SMIL), then anchors that sentence by its bundle
     *  text so it resolves on the ABS EPUB (the same text-anchoring the highlight uses). `null` when
     *  the seconds can't be placed or the sentence has no quote. */
    fun ebookLocatorForAudioSeconds(seconds: Double): String? {
        val ref = fragmentForAudioSeconds(seconds) ?: return null
        val quote = quotes[ref.substringAfter('#')] ?: return null
        return readaloudLocatorJson(ref, quote).toString()
    }

    /** PATCH the ABS audiobook record from the narrated sentence; returns the server stamp or `null`. */
    suspend fun pushFragment(fragmentRef: String): Long? =
        secondsForFragment(fragmentRef)?.let { absApi.writeAudiobookSeconds(endpoint, it) }

    /**
     * The readaloud resume anchor for an audio second, index-free via the bundle SMIL (ADR 0031): maps
     * seconds → narrated sentence and carries that sentence ref. Lets the audiobook player persist the
     * readaloud resume so reopening the reader and pressing Play lands on the listened sentence instead
     * of a stale one — even without the cross-EPUB index. Unlike [ebookLocatorForAudioSeconds] it needs
     * no quote: the readaloud start only needs the sentence ref, not a text anchor. `null` when the
     * seconds narrate no sentence.
     */
    fun readaloudAnchorForAudioSeconds(seconds: Double): com.riffle.core.domain.ReadaloudResumePosition? {
        val ref = fragmentForAudioSeconds(seconds) ?: return null
        val href = ref.substringBefore('#').takeIf { it.isNotEmpty() } ?: return null
        return com.riffle.core.domain.ReadaloudResumePosition(href = href, progression = null, fragmentRef = ref)
    }
}

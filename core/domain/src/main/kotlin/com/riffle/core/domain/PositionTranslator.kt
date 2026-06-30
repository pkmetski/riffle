package com.riffle.core.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Single point of conversion between the three coordinate systems a matched book holds a
 * position in (ADR 0019, as amended by ADRs 0026 and 0029 — a book is always read from
 * the ABS side, so the canonical frame is always the displayed ABS EPUB):
 *
 *  - **audio-seconds** — an offset into the ABS audiobook's concatenated audio.
 *  - **canonical (displayed-EPUB Locator JSON)** — the reader's native position.
 *  - **ABS-EPUB CFI** — `epubcfi(...)` as ABS stores it (ADR 0013).
 *  - **Storyteller-EPUB Locator JSON** — a position on the Storyteller publication.
 *
 * Every conversion is best-effort: an input that cannot be placed in the target system
 * returns `null` rather than guessing, so a missing mapping degrades a sync cycle to a
 * deferred PATCH instead of a wrong one.
 *
 * One translator is built per open book, memoising the spine-index map and per-file SMIL
 * absolute-offset table once; continuous-mode scroll then translates per frame without
 * re-parsing SMIL or re-walking spine lists.
 */
interface PositionTranslator {

    // ── SMIL-only (work without spine/HTML/cross-EPUB index) ───────────────────

    /** Audio time the narrated fragment (`href#id`) begins. `null` for an unknown fragment. */
    fun fragmentRefToAudioSeconds(textFragmentRef: String): Double?

    /** Audio time → narrated Storyteller text fragment. `null` when no clip covers the time. */
    fun audioSecondsToTextFragment(seconds: Double): String?

    /** Audio time → canonical (Storyteller-EPUB) progression. */
    fun audioSecondsToStorytellerProgression(seconds: Double): ChapterProgression?

    /** Canonical (Storyteller-EPUB) progression → audio time at the latest narrated
     *  fragment at or before [pos] within the same chapter. */
    fun storytellerProgressionToAudioSeconds(pos: ChapterProgression): Double?

    /** The narrated fragment at or before [pos] within the same chapter. */
    fun fragmentAt(pos: ChapterProgression): String?

    // ── Cross-EPUB progression (require cross-EPUB index) ──────────────────────

    fun storytellerToAbsProgression(pos: ChapterProgression): ChapterProgression?
    fun absToStorytellerProgression(pos: ChapterProgression): ChapterProgression?
    fun absBookProgression(pos: ChapterProgression): Double?

    // ── Canonical (displayed-EPUB Locator JSON) seam ───────────────────────────

    /** ABS `epubcfi(...)` → canonical Locator JSON on the displayed (ABS) EPUB. */
    fun absCfiToCanonical(cfi: String): String?

    /** Canonical Locator JSON → ABS `epubcfi(...)`. */
    fun canonicalToAbsCfi(locatorJson: String): String?

    /** Book-wide progress (0..1) for the ABS progress bar — uses the locator's
     *  `totalProgression` when present, else weights chapters by character count. */
    fun canonicalBookProgress(locatorJson: String): Float

    /** Storyteller-EPUB Locator JSON → canonical (displayed-EPUB) Locator JSON. */
    fun storytellerLocatorToCanonical(stLocatorJson: String): String?

    /** Canonical → Storyteller-EPUB Locator JSON. */
    fun canonicalToStorytellerLocator(locatorJson: String): String?

    /** Audio second → canonical Locator JSON on the displayed (ABS) EPUB. */
    fun audioSecondsToCanonical(seconds: Double): String?

    /** Canonical Locator JSON → audio second. */
    fun canonicalToAudioSeconds(locatorJson: String): Double?

    /** Exact audio time a narrated fragment begins (sentence-precise). */
    fun audioSecondsForFragment(textFragmentRef: String): Double?

    /** Narrated Storyteller fragment an audio time falls in. */
    fun fragmentForAudioSeconds(seconds: Double): String?

    /** Narrated Storyteller fragment a canonical reading position falls in. */
    fun canonicalToFragmentRef(locatorJson: String): String?

    /** Storyteller bundle spine href for a displayed (ABS) href — spine-aligned by index. */
    fun displayedHrefToBundleHref(displayedHref: String): String?
}

/**
 * Default [PositionTranslator]. Composes:
 *  - SMIL clips → absolute audio timeline + fragment lookups,
 *  - cross-EPUB index → progression remap,
 *  - displayed (ABS) + Storyteller spine hrefs + chapter HTML → CFI / Locator JSON edges.
 *
 * Pure (jsoup + kotlinx.serialization.json), unit-testable. Construct once per open book.
 *
 * Bundle-only mode: pass only [smilClips] and leave the other arguments at defaults.
 * Then the SMIL-only methods work; cross-EPUB and Locator/CFI methods return `null`.
 */
class DefaultPositionTranslator(
    smilClips: List<MediaOverlayClip>,
    private val crossEpubIndex: CrossEpubIndex = CrossEpubIndex(emptyList()),
    private val fragmentProgressions: Map<String, ChapterProgression> = emptyMap(),
    private val absSpineHrefs: List<String> = emptyList(),
    private val absChapterHtml: (Int) -> String? = { null },
    private val storytellerSpineHrefs: List<String> = emptyList(),
    private val storytellerChapterHtml: (Int) -> String? = { null },
) : PositionTranslator {

    /**
     * SMIL clip times are **per audio file** — each file's clips restart near 0. The ABS audiobook is
     * those files concatenated into one timeline, so to translate a clip to an absolute ABS
     * `currentTime` we add the cumulative duration of every file before it, in playback (document)
     * order. A file's duration is taken as its largest `clipEnd`. A single-file bundle is unchanged.
     */
    private val absoluteClips: List<MediaOverlayClip> = run {
        val fileOrder = LinkedHashSet<String>().apply { smilClips.forEach { add(it.audioSrc) } }
        val fileDuration = smilClips.groupBy { it.audioSrc }.mapValues { e -> e.value.maxOf { it.clipEndSec } }
        val offsetOf = HashMap<String, Double>()
        var acc = 0.0
        for (file in fileOrder) { offsetOf[file] = acc; acc += fileDuration[file] ?: 0.0 }
        smilClips.map { c ->
            val offset = offsetOf[c.audioSrc] ?: 0.0
            if (offset == 0.0) c else c.copy(clipBeginSec = c.clipBeginSec + offset, clipEndSec = c.clipEndSec + offset)
        }
    }

    // Absolute clip-begin keyed by the fragment ref with "."/".." path segments collapsed, so a
    // player-supplied ref ("text/x.html#id") matches the SMIL clip ref ("../text/x.html#id").
    private val absSecondsByResolvedFragment: Map<String, Double> =
        absoluteClips.associate { resolveEpubHref(it.textFragmentRef) to it.clipBeginSec }

    // Memoised normalized-href → spine-index lookups for both EPUBs — continuous-mode scroll
    // hits these per frame, so a list scan per call adds up.
    private val absIndexByHref: Map<String, Int> =
        absSpineHrefs.withIndex().associate { (i, h) -> normalizeEpubHref(h) to i }
    private val storytellerIndexByHref: Map<String, Int> =
        storytellerSpineHrefs.withIndex().associate { (i, h) -> normalizeEpubHref(h) to i }

    // ── SMIL-only ──────────────────────────────────────────────────────────────

    override fun fragmentRefToAudioSeconds(textFragmentRef: String): Double? =
        absSecondsByResolvedFragment[resolveEpubHref(textFragmentRef)]

    override fun audioSecondsToTextFragment(seconds: Double): String? =
        absoluteClips.firstOrNull { seconds >= it.clipBeginSec && seconds < it.clipEndSec }
            ?.textFragmentRef

    override fun audioSecondsToStorytellerProgression(seconds: Double): ChapterProgression? =
        audioSecondsToTextFragment(seconds)?.let { fragmentProgressions[it] }

    override fun storytellerProgressionToAudioSeconds(pos: ChapterProgression): Double? =
        fragmentAt(pos)?.let { textFragmentToAudioSeconds(it) }

    override fun fragmentAt(pos: ChapterProgression): String? =
        fragmentProgressions.entries
            .filter { it.value.chapterIndex == pos.chapterIndex && it.value.progression <= pos.progression }
            .maxByOrNull { it.value.progression }
            ?.key

    private fun textFragmentToAudioSeconds(textFragmentRef: String): Double? =
        absoluteClips.firstOrNull { it.textFragmentRef == textFragmentRef }?.clipBeginSec

    // ── Cross-EPUB progression ─────────────────────────────────────────────────

    override fun storytellerToAbsProgression(pos: ChapterProgression): ChapterProgression? =
        remap(pos, fromChars = { it.storytellerChars }, toChars = { it.absChars })

    override fun absToStorytellerProgression(pos: ChapterProgression): ChapterProgression? =
        remap(pos, fromChars = { it.absChars }, toChars = { it.storytellerChars })

    override fun absBookProgression(pos: ChapterProgression): Double? {
        val chapters = crossEpubIndex.perChapter
        val chapter = chapters.getOrNull(pos.chapterIndex) ?: return null
        val total = chapters.sumOf { it.absChars }
        if (total <= 0L) return null
        val before = chapters.take(pos.chapterIndex).sumOf { it.absChars }
        val within = pos.progression.coerceIn(0.0, 1.0) * chapter.absChars
        return ((before + within) / total).coerceIn(0.0, 1.0)
    }

    private inline fun remap(
        pos: ChapterProgression,
        fromChars: (ChapterCharMap) -> Long,
        toChars: (ChapterCharMap) -> Long,
    ): ChapterProgression? {
        val map = crossEpubIndex.perChapter.getOrNull(pos.chapterIndex) ?: return null
        val to = toChars(map)
        if (to == 0L) return null
        val charOffset = pos.progression * fromChars(map)
        return ChapterProgression(pos.chapterIndex, (charOffset / to).coerceIn(0.0, 1.0))
    }

    // ── Canonical (displayed-EPUB Locator JSON) ↔ ABS ChapterProgression ───────

    private fun canonicalToAbsProgression(locatorJson: String): ChapterProgression? {
        val obj = parseJsonObject(locatorJson) ?: return null
        val href = (obj["href"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val idx = absIndexByHref[normalizeEpubHref(href)] ?: return null
        val progression = (obj["locations"] as? JsonObject)
            ?.let { (it["progression"] as? JsonPrimitive)?.doubleOrNull } ?: 0.0
        return ChapterProgression(idx, progression)
    }

    private fun absProgressionToCanonical(p: ChapterProgression, totalProgression: Double? = null): String? {
        val href = absSpineHrefs.getOrNull(p.chapterIndex) ?: return null
        return buildJsonObject {
            put("href", href)
            put("type", "application/xhtml+xml")
            put("locations", buildJsonObject {
                put("progression", p.progression)
                if (totalProgression != null) put("totalProgression", totalProgression)
            })
        }.toString()
    }

    // ── ABS CFI ↔ canonical ────────────────────────────────────────────────────

    override fun absCfiToCanonical(cfi: String): String? {
        val idx = epubCfiToSpineIndex(cfi) ?: return null
        val docPath = extractCfiDocPath(cfi) ?: return null
        val html = absChapterHtml(idx) ?: return null
        val progression = cfiDocPathToProgression(docPath, html) ?: return null
        return absProgressionToCanonical(ChapterProgression(idx, progression))
    }

    override fun canonicalToAbsCfi(locatorJson: String): String? {
        val p = canonicalToAbsProgression(locatorJson) ?: return null
        val html = absChapterHtml(p.chapterIndex) ?: return null
        val docPath = progressionToCfiDocPath(p.progression, html) ?: return null
        val spineStep = (p.chapterIndex + 1) * 2
        return "epubcfi(/6/$spineStep!$docPath)"
    }

    override fun canonicalBookProgress(locatorJson: String): Float {
        CanonicalReaderPosition(locatorJson).totalProgression?.let { return it.toFloat() }
        val p = canonicalToAbsProgression(locatorJson) ?: return 0f
        return (absBookProgression(p) ?: p.progression).toFloat()
    }

    // ── Storyteller Locator ↔ canonical ────────────────────────────────────────

    override fun storytellerLocatorToCanonical(stLocatorJson: String): String? {
        val obj = parseJsonObject(stLocatorJson) ?: return null
        val href = (obj["href"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val idx = storytellerIndexByHref[normalizeEpubHref(href)] ?: return null
        val progression = (obj["locations"] as? JsonObject)
            ?.let { (it["progression"] as? JsonPrimitive)?.doubleOrNull } ?: 0.0
        return storytellerToAbsProgression(ChapterProgression(idx, progression))
            ?.let { absProgressionToCanonical(it) }
    }

    override fun canonicalToStorytellerLocator(locatorJson: String): String? {
        val abs = canonicalToAbsProgression(locatorJson) ?: return null
        val storyteller = absToStorytellerProgression(abs) ?: return null
        val href = storytellerSpineHrefs.getOrNull(storyteller.chapterIndex) ?: return null
        return buildJsonObject {
            put("href", href)
            put("type", "application/xhtml+xml")
            put("locations", buildJsonObject { put("progression", storyteller.progression) })
        }.toString()
    }

    // ── Audio seconds ↔ canonical ──────────────────────────────────────────────

    override fun audioSecondsToCanonical(seconds: Double): String? =
        audioSecondsToStorytellerProgression(seconds)
            ?.let { storytellerToAbsProgression(it) }
            ?.let { absProgressionToCanonical(it) }

    override fun canonicalToAudioSeconds(locatorJson: String): Double? =
        canonicalToAbsProgression(locatorJson)
            ?.let { absToStorytellerProgression(it) }
            ?.let { storytellerProgressionToAudioSeconds(it) }

    override fun audioSecondsForFragment(textFragmentRef: String): Double? =
        fragmentRefToAudioSeconds(textFragmentRef)

    override fun fragmentForAudioSeconds(seconds: Double): String? =
        audioSecondsToStorytellerProgression(seconds)?.let { fragmentAt(it) }

    override fun canonicalToFragmentRef(locatorJson: String): String? =
        canonicalToAbsProgression(locatorJson)
            ?.let { absToStorytellerProgression(it) }
            ?.let { fragmentAt(it) }

    override fun displayedHrefToBundleHref(displayedHref: String): String? {
        val idx = absIndexByHref[normalizeEpubHref(displayedHref)] ?: return null
        return storytellerSpineHrefs.getOrNull(idx)
    }

    private fun parseJsonObject(value: String): JsonObject? =
        runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
}

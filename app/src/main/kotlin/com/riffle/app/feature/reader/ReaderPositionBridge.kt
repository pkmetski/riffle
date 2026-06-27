package com.riffle.app.feature.reader

import com.riffle.core.domain.CanonicalPositionTranslator
import com.riffle.core.domain.CanonicalReaderPosition
import com.riffle.core.domain.ChapterProgression
import org.json.JSONObject

/**
 * Converts the reader's canonical position (a Readium Locator JSON on the displayed **ABS** EPUB)
 * to and from each peer's native coordinate for a matched readaloud book (ADR 0019, as amended by
 * ADR 0026 — a book is always read from the ABS side, so the canonical frame is always the ABS
 * EPUB and ABS ebook is always the native peer):
 *
 *  - ABS ebook → a CFI in the ABS EPUB,
 *  - ABS audiobook → seconds (via the Storyteller SMIL),
 *  - Storyteller → a Locator on the Storyteller EPUB.
 *
 * It owns the spine hrefs and chapter HTML of **both** EPUBs plus the
 * [CanonicalPositionTranslator]; every conversion routes through a spine-aligned
 * [ChapterProgression] and returns `null` when it can't be placed, so a missing mapping
 * defers a PATCH rather than producing a wrong one. Pure (JSON + jsoup), unit-testable.
 */
class ReaderPositionBridge(
    private val absSpineHrefs: List<String>,
    private val absChapterHtml: (Int) -> String?,
    private val storytellerSpineHrefs: List<String>,
    private val storytellerChapterHtml: (Int) -> String?,
    private val translator: CanonicalPositionTranslator,
) {
    private val displayedDomain = Domain.ABS

    private enum class Domain { ABS, ST }

    // ── Canonical (displayed-EPUB Locator JSON) ↔ displayed ChapterProgression ──────

    private fun spineHrefs(domain: Domain) = if (domain == Domain.ABS) absSpineHrefs else storytellerSpineHrefs

    private fun canonicalToDisplayedProgression(locatorJson: String): ChapterProgression? {
        val obj = runCatching { JSONObject(locatorJson) }.getOrNull() ?: return null
        val href = obj.optString("href").takeIf { it.isNotEmpty() } ?: return null
        val idx = spineIndexOfHref(spineHrefs(displayedDomain), href).takeIf { it >= 0 } ?: return null
        val progression = obj.optJSONObject("locations")?.optDouble("progression", 0.0) ?: 0.0
        return ChapterProgression(idx, progression)
    }

    private fun displayedProgressionToCanonical(p: ChapterProgression, totalProgression: Double?): String? {
        val href = spineHrefs(displayedDomain).getOrNull(p.chapterIndex) ?: return null
        val locations = JSONObject().put("progression", p.progression)
        if (totalProgression != null) locations.put("totalProgression", totalProgression)
        return JSONObject()
            .put("href", href)
            .put("type", "application/xhtml+xml")
            .put("locations", locations)
            .toString()
    }

    /** Cross-domain progression conversion (chapter index is spine-aligned; only progression scales). */
    private fun convert(p: ChapterProgression, from: Domain, to: Domain): ChapterProgression? = when {
        from == to -> p
        from == Domain.ABS -> translator.absToStorytellerProgression(p)
        else -> translator.storytellerToAbsProgression(p)
    }

    private fun toCanonical(p: ChapterProgression, from: Domain): String? =
        convert(p, from, displayedDomain)?.let { displayedProgressionToCanonical(it, null) }

    private fun fromCanonical(locatorJson: String, to: Domain): ChapterProgression? =
        canonicalToDisplayedProgression(locatorJson)?.let { convert(it, displayedDomain, to) }

    // ── ABS ebook (CFI) ────────────────────────────────────────────────────────────

    fun absCfiToCanonical(cfi: String): String? {
        val idx = epubCfiToSpineIndex(cfi) ?: return null
        val docPath = extractCfiDocPath(cfi) ?: return null
        val html = absChapterHtml(idx) ?: return null
        val progression = cfiDocPathToProgression(docPath, html) ?: return null
        return toCanonical(ChapterProgression(idx, progression), Domain.ABS)
    }

    fun canonicalToAbsCfi(locatorJson: String): String? {
        val p = fromCanonical(locatorJson, Domain.ABS) ?: return null
        val html = absChapterHtml(p.chapterIndex) ?: return null
        val docPath = progressionToCfiDocPath(p.progression, html) ?: return null
        val spineStep = (p.chapterIndex + 1) * 2
        return "epubcfi(/6/$spineStep!$docPath)"
    }

    /**
     * Book-wide progress float for the ABS progress bar. A local reading position carries an explicit
     * `totalProgression`; a canonical reconstructed from a remote (audiobook / Storyteller) does not,
     * so we compute it from the chapter character weights. Without that fallback, propagating a
     * remote win to the ebook would write progress 0 and clear the server's progress bar.
     */
    fun canonicalBookProgress(locatorJson: String): Float {
        CanonicalReaderPosition(locatorJson).totalProgression?.let { return it.toFloat() }
        val p = canonicalToDisplayedProgression(locatorJson) ?: return 0f
        return (translator.absBookProgression(p) ?: p.progression).toFloat()
    }

    // ── Storyteller (Locator on the Storyteller EPUB) ───────────────────────────────

    fun storytellerLocatorToCanonical(stLocatorJson: String): String? {
        val obj = runCatching { JSONObject(stLocatorJson) }.getOrNull() ?: return null
        val href = obj.optString("href").takeIf { it.isNotEmpty() } ?: return null
        val idx = spineIndexOfHref(storytellerSpineHrefs, href).takeIf { it >= 0 } ?: return null
        val progression = obj.optJSONObject("locations")?.optDouble("progression", 0.0) ?: 0.0
        return toCanonical(ChapterProgression(idx, progression), Domain.ST)
    }

    fun canonicalToStorytellerLocator(locatorJson: String): String? {
        val p = fromCanonical(locatorJson, Domain.ST) ?: return null
        val href = storytellerSpineHrefs.getOrNull(p.chapterIndex) ?: return null
        return JSONObject()
            .put("href", href)
            .put("type", "application/xhtml+xml")
            .put("locations", JSONObject().put("progression", p.progression))
            .toString()
    }

    // ── ABS audiobook (seconds, via SMIL on the Storyteller EPUB) ────────────────────

    fun audioSecondsToCanonical(seconds: Double): String? =
        translator.audioSecondsToStorytellerProgression(seconds)?.let { toCanonical(it, Domain.ST) }

    fun canonicalToAudioSeconds(locatorJson: String): Double? =
        fromCanonical(locatorJson, Domain.ST)?.let { translator.storytellerProgressionToAudioSeconds(it) }

    /** The exact absolute audio time the given narrated fragment (`href#id`) begins. */
    fun audioSecondsForFragment(textFragmentRef: String): Double? =
        translator.fragmentRefToAudioSeconds(textFragmentRef)

    /** The narrated Storyteller fragment (`href#id`) an absolute audio time falls in — bundle-SMIL
     *  only (no cross-EPUB index), so a listen position can seed the readaloud start (ADR 0031). */
    fun fragmentForAudioSeconds(seconds: Double): String? =
        translator.audioSecondsToStorytellerProgression(seconds)?.let { translator.fragmentAt(it) }

    /** The narrated Storyteller fragment (`href#id`) a canonical reading position falls in — the
     *  sentence readaloud should start at when a server sync placed the reader here. */
    fun canonicalToFragmentRef(locatorJson: String): String? =
        fromCanonical(locatorJson, Domain.ST)?.let { translator.fragmentAt(it) }

    /**
     * The Storyteller-bundle spine href for the displayed (ABS) chapter [displayedHref] — spine-aligned
     * by index (ADR 0019). "Play from here" carries the rendered ABS href plus a Storyteller span id;
     * since span ids recur across chapters, the player needs the bundle chapter the selection sits in to
     * pick the right clip. Null when the href isn't a known ABS spine entry, so the caller keeps the
     * original ref (the player then falls back to a bare-id match). The chapter index is shared between
     * the two EPUBs; only the href scheme differs, which [ReadaloudTrack] reconciles.
     */
    fun displayedHrefToBundleHref(displayedHref: String): String? {
        val idx = spineIndexOfHref(absSpineHrefs, displayedHref).takeIf { it >= 0 } ?: return null
        return storytellerSpineHrefs.getOrNull(idx)
    }

    private fun spineIndexOfHref(hrefs: List<String>, href: String): Int {
        val target = normalizeEpubHref(href)
        return hrefs.indexOfFirst { normalizeEpubHref(it) == target }
    }
}

package com.riffle.app.feature.reader

import com.riffle.core.domain.CanonicalPositionTranslator
import com.riffle.core.domain.ChapterProgression
import com.riffle.core.domain.OpenedSide
import org.json.JSONObject

/**
 * Converts the reader's canonical position (a Readium Locator JSON on the **displayed** EPUB)
 * to and from each peer's native coordinate for a matched readaloud book (ADR 0019):
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
    private val displayedSide: OpenedSide,
    private val absSpineHrefs: List<String>,
    private val absChapterHtml: (Int) -> String?,
    private val storytellerSpineHrefs: List<String>,
    private val storytellerChapterHtml: (Int) -> String?,
    private val translator: CanonicalPositionTranslator,
) {
    private val displayedDomain = if (displayedSide == OpenedSide.ABS) Domain.ABS else Domain.ST

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

    /** Book-wide progress float for the ABS progress bar; best-effort from the canonical locator. */
    fun canonicalBookProgress(locatorJson: String): Float =
        runCatching { JSONObject(locatorJson).optJSONObject("locations")?.optDouble("totalProgression", 0.0) ?: 0.0 }
            .getOrDefault(0.0).toFloat()

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

    private fun spineIndexOfHref(hrefs: List<String>, href: String): Int {
        val target = normalizeEpubHref(href)
        return hrefs.indexOfFirst { normalizeEpubHref(it) == target }
    }
}

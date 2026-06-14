package com.riffle.core.domain

/**
 * Converts ebook position strings across the Riffle↔ABS format boundary (ADR 0013):
 * - ABS stores `epubcfi(...)` — the epub.js DOM-path format
 * - Riffle stores Readium Locator JSON — `{"href":"...","locations":{"progression":0.64}}`
 *
 * Each direction requires the EPUB's spine (to map spine step ↔ chapter href) and the
 * chapter's HTML (to map DOM-path character offset ↔ 0–1 within-chapter progression). An
 * implementation that can't locate the cached EPUB should return null from [forItem].
 */
interface EbookCfiTranslator {
    /** ABS `epubcfi(...)` → Riffle Locator JSON. Returns null if the EPUB isn't cached or the
     *  CFI can't be resolved — callers should treat this as "defer" (leave the row dirty). */
    suspend fun cfiToLocatorJson(epubcfi: String): String?
    /** Riffle Locator JSON → ABS `epubcfi(...)`. Accepts a legacy raw `epubcfi(...)` passthrough.
     *  Returns null if translation fails — callers should treat this as "skip this PATCH". */
    suspend fun locatorJsonToCfi(locatorJson: String): String?
}

/** Produces a per-item [EbookCfiTranslator]. Returns null when the EPUB for [itemId] is not
 *  locally cached (translation is impossible without the chapter HTML). */
interface EbookCfiTranslatorFactory {
    fun forItem(serverId: String, itemId: String): EbookCfiTranslator?
}

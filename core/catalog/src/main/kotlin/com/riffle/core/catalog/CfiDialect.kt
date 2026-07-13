package com.riffle.core.catalog

/**
 * Which position dialect a [ProgressPeerCapability] stores its ebook position in (ADR 0013,
 * ADR 0041, refined for source-agnostic peers by #528).
 *
 * ABS stores epub.js-style `epubcfi(...)` — different from Readium's native CFI. When the peer
 * uses [EPUB_JS], writes/reads must round-trip through the
 * [com.riffle.core.domain.EbookCfiTranslator] at the Catalog boundary so the local store keeps a
 * single canonical Locator JSON. When the peer uses [READIUM_NATIVE] the local Locator JSON is
 * what the peer already speaks — no translation. [PAGE_NUMBER] means the peer speaks an opaque
 * page-number (or other numeric) position: no CFI translation, position bytes pass through both
 * ways (Komga's `page` field, #528).
 *
 * A LocalFiles-style peer that never writes progress remotely is irrelevant here (it isn't a
 * `ProgressPeerCapability` in the first place).
 */
enum class CfiDialect {
    /** Peer stores epub.js `epubcfi(/6/4!/4)`; translation to/from Readium Locator JSON is required. */
    EPUB_JS,

    /** Peer stores the Readium Locator JSON as-is; the translator MUST be skipped. */
    READIUM_NATIVE,

    /**
     * Peer stores an opaque, non-CFI position (e.g. Komga's `page` integer, encoded as a string).
     * No CFI translator is invoked — the payload passes through both ways verbatim. The peer
     * decides the encoding.
     */
    PAGE_NUMBER,
}

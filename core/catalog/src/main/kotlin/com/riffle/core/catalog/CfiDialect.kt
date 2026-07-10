package com.riffle.core.catalog

/**
 * Which CFI dialect a [ProgressPeerCapability] stores its ebook position in (ADR 0013, ADR 0041).
 *
 * ABS stores epub.js-style `epubcfi(...)` — different from Readium's native CFI. When the peer
 * uses [EPUB_JS], writes/reads must round-trip through the [com.riffle.core.domain.EbookCfiTranslator]
 * at the Catalog boundary so the local store keeps a single canonical Locator JSON. When the peer
 * uses [READIUM_NATIVE] the local Locator JSON is what the peer already speaks — no translation.
 *
 * A LocalFiles-style peer that never writes progress remotely is irrelevant here (it isn't a
 * `ProgressPeerCapability` in the first place).
 */
enum class CfiDialect {
    /** Peer stores epub.js `epubcfi(/6/4!/4)`; translation to/from Readium Locator JSON is required. */
    EPUB_JS,

    /** Peer stores the Readium Locator JSON as-is; the translator MUST be skipped. */
    READIUM_NATIVE,
}

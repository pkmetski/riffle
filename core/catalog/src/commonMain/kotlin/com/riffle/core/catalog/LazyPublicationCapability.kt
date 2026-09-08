package com.riffle.core.catalog

/**
 * Opt-in mixin for sources that can open an ebook lazily — building the publication shape from
 * metadata only (no content bytes) and streaming chapter content on demand as Readium renders it.
 *
 * Gated on this capability so the shared reader open path stays source-agnostic — same pattern as
 * [EbookDetailsCapability]. Only [com.riffle.core.models.SourceType.OREILLY] implements this
 * today; every other source falls through to the normal file-based route.
 *
 * iOS: the fetch + disk-cache logic ([fetchChapterForLazy] / [fetchAssetForLazy]) stays in
 * commonMain and is usable from iOS. The Readium-iOS equivalent of Container/Resource is
 * ReadiumSwift's Resource/Container; the iOS lazy open path requires a separate iosMain
 * implementation once iOS reading is active. Tracked in a follow-up issue.
 */
interface LazyPublicationCapability : CatalogCapability {
    /**
     * Returns the book shape (metadata + spine order + file sizes) from cheap API calls, with no
     * chapter content fetched. The caller uses this to build a lazy Readium Publication backed by
     * an on-demand fetching container.
     *
     * Returns null when the spine is empty or unreachable.
     */
    suspend fun lazyPublication(itemId: String): LazyPublicationShape?

    /**
     * Fetch one chapter's HTML for the lazy-reading path. Applies the same backoff/truncation
     * logic as the full synthesize path. Returns null after exhausting retries.
     */
    suspend fun fetchChapterForLazy(itemId: String, fullPath: String): String?

    /**
     * Fetch one asset's bytes for the lazy-reading path (non-fatal — returns null on failure).
     */
    suspend fun fetchAssetForLazy(itemId: String, fullPath: String): ByteArray?
}

/**
 * Publication shape computed from source metadata — spine order, file metadata, and book identity
 * — with no chapter HTML downloaded. Carries everything needed to build a Readium Manifest and
 * to make per-chapter fetch calls later via [LazyPublicationCapability].
 */
data class LazyPublicationShape(
    val bookId: String,
    val identifier: String,
    val title: String,
    val language: String,
    val spine: List<LazySpineItem>,
    /** Absolute base URL prefix of chapter content, used for absolute→relative asset URL rewriting. */
    val absoluteFilesPrefix: String,
    /** Path-only prefix (e.g. `/api/v2/epubs/…`), also used in URL rewriting. */
    val pathFilesPrefix: String,
    /** Full paths of all stylesheets, used to inject `<link>` tags into each chapter. */
    val cssFullPaths: List<String>,
)

/** One entry in the reading order of a [LazyPublicationShape]. */
data class LazySpineItem(
    val index: Int,
    val fullPath: String,
    val title: String,
    /** Declared byte size. Used for truncation detection and Readium position count estimation. */
    val declaredByteSize: Long,
    val mediaType: String,
)

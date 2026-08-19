package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelStore
import java.io.File
import javax.inject.Inject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-disk [PanelStore] — one JSON file per book, holding a list of [PagePanels]. Reads and writes
 * are file-level; concurrent writes for different books are safe, but concurrent writes for the
 * same book race and the last writer wins. That's fine: within one reader session all writes go
 * through a single orchestrator, and cross-session races would just recompute a page whose result
 * is deterministic anyway.
 *
 * File layout: `<rootDir>/<bookId-safe>.json` where `<bookId-safe>` is the `bookId` with any
 * character outside `[A-Za-z0-9._-]` replaced by `_`. The file itself carries the original
 * `bookId` so a collision on the safe filename doesn't produce a wrong load — we sanity-check
 * on read.
 */
class JsonPanelStore @Inject constructor(
    private val rootDir: File,
) : PanelStore {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    init {
        rootDir.mkdirs()
    }

    override fun load(bookId: String, pageIndex: Int): PagePanels? =
        loadAll(bookId)[pageIndex]

    override fun loadAll(bookId: String): Map<Int, PagePanels> {
        val file = fileFor(bookId)
        if (!file.exists()) return emptyMap()
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyMap()
        val doc = runCatching { json.decodeFromString(BookFile.serializer(), text) }.getOrNull()
            ?: return emptyMap()
        // Any mismatch (wrong bookId → filename collision, older schema version → detector change)
        // is treated as a miss so we re-detect on the next open. Older schema versions get
        // silently overwritten when save/saveAll writes the current version back.
        if (doc.bookId != bookId || doc.schemaVersion != CURRENT_SCHEMA_VERSION) return emptyMap()
        return doc.pages.associateBy { it.pageIndex }
    }

    override fun save(bookId: String, page: PagePanels) {
        val existing = loadAll(bookId).toMutableMap()
        existing[page.pageIndex] = page
        writeBook(bookId, existing.values.sortedBy { it.pageIndex })
    }

    override fun saveAll(bookId: String, pages: Collection<PagePanels>) {
        val existing = loadAll(bookId).toMutableMap()
        for (page in pages) existing[page.pageIndex] = page
        writeBook(bookId, existing.values.sortedBy { it.pageIndex })
    }

    override fun clear(bookId: String) {
        fileFor(bookId).delete()
    }

    private fun writeBook(bookId: String, pages: List<PagePanels>) {
        val doc = BookFile(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            bookId = bookId,
            pages = pages,
        )
        val tmp = File(rootDir, "${safe(bookId)}.json.tmp")
        tmp.writeText(json.encodeToString(BookFile.serializer(), doc), Charsets.UTF_8)
        if (!tmp.renameTo(fileFor(bookId))) {
            // Rename can fail across some FUSE filesystems; fall back to direct write.
            fileFor(bookId).writeText(json.encodeToString(BookFile.serializer(), doc), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun fileFor(bookId: String): File = File(rootDir, "${safe(bookId)}.json")

    private fun safe(bookId: String): String = bookId.replace(UNSAFE, "_")

    @Serializable
    private data class BookFile(
        // Missing on files written before the field was added (v1) — Serializable defaults it to
        // 1, so those pre-versioning caches read back as v1 and mismatch CURRENT_SCHEMA_VERSION.
        val schemaVersion: Int = 1,
        val bookId: String,
        val pages: List<PagePanels>,
    )

    companion object {
        /**
         * Bump when the detector output changes materially (algorithm, coordinate space, panel
         * geometry). Files written with a different version are treated as a cache miss.
         *
         * History:
         *  1 — original single-pass value-based binarize + auto-invert (first landed panel view).
         *  2 — two-pass content-vs-background classifier; auto-invert removed. Files written under
         *      v1 held Fallback results for dark-gutter comics that the v2 detector handles.
         *  3 — projection-based grid detector as the primary path; connected-component becomes
         *      the fallback for irregular layouts. Different panel geometry from v2 on the same
         *      page — invalidate to re-detect.
         *  4 — sanity check rejects panels smaller than 15% of a page dimension AND rejects
         *      detections whose total panel coverage is < 40% of the page. Prevents Panel View
         *      from forcing users through noise-island zooms on bleed-splash pages. v3 caches
         *      held those garbage detections — invalidate to re-detect them as Fallback.
         *  5 — split-at-internal-gutter post-processing runs on every candidate bbox in both
         *      the projection and CC paths. A bbox that straddles a full-crossing internal
         *      gutter is now split into its two real panels. v4 caches held wrongly-merged
         *      bboxes for those pages.
         *  6 — dedup pass drops merged-panel duplicates that would cause Panel View to walk
         *      the user through the same real panel twice (once tight, once as part of a
         *      larger merged bbox). v5 caches held those duplicates.
         *  7 — PanelSource enum shrunk to Auto/Fallback (Acbf/ComicInfo removed since the
         *      ACBF sidecar path was never actually reachable in production). v6 caches would
         *      fail to deserialize the removed enum values.
         *  8 — internal-gutter split criterion switched from content-count (5%) to flood-fill
         *      fraction (30%) so hollow panel interiors (speech balloons, white fills) no longer
         *      trigger false splits. projection path loses splitAtInternalGutters; instead a
         *      suspiciousWideRow check detects under-split projection rows and hands them to CC.
         *      v7 caches held either wrong splits or wrong merges from the old heuristic.
         * v9: gridByProjection no longer returns null when only SOME rows are suspicious.
         *      Suspicious rows are now kept as full-width single-cell panels. Pages with a
         *      full-width splash row above/below multi-column rows now emit the correct 3+
         *      panel layout instead of falling back to CC which could produce wrong splits.
         *      v8 caches for splash+panels layouts may have wrong panel count.
         * v10: detectBackgroundLuma now uses 85th-percentile of full-border samples instead of
         *      median of 8 corner/midpoint samples. Scanned pages with dark book-spine corners
         *      previously had background misestimated as ~140 (median biased by dark corners)
         *      instead of ~210 (actual tan paper), causing the gutter to be classified as
         *      content and the entire detection to fall back to Fallback. v9 caches for those
         *      pages hold Fallback results that must be re-detected.
         * v11: binarize now uses one-sided contrast (bg − v ≥ threshold for light backgrounds,
         *      v − bg ≥ threshold for dark backgrounds). White speech bubble interiors (lighter
         *      than the page background) are no longer classified as content, so flood fill can
         *      flow through them as gutter. This prevents speech bubbles sitting in the gutter
         *      between panels from acting as content bridges that cause adjacent panels to be
         *      merged into one CC and missed. v10 caches for pages with gutter speech bubbles
         *      may hold wrong (under-detected) panel counts.
         * v12: gridByProjection now applies the same flood-fill split used in the CC path to
         *      all projection bboxes. Previously a gutter narrower than projectionMinBandThickness
         *      (15px) would cause adjacent rows to merge into one tall column strip with no
         *      recovery; now splitSinglePanelRecursively detects the genuine inter-panel gutter
         *      (flood-fill accessible from page border → ~100%) and splits the strip correctly.
         *      v11 caches for pages with narrow row gutters may hold under-split column strips.
         * v13: splitSinglePanelRecursively now skips a split if either resulting sub-bbox would
         *      be smaller than minPanelDimensionFraction of the page. Fixes panels with a caption
         *      box whose left/right edge touches the page border: flood-fill entered the caption
         *      interior through the border, making the narrow margin below the caption qualify as
         *      an internal gutter; the split produced a caption-only piece that fell below the
         *      15% dimension floor and was dropped, cutting off the top of the real panel. v12
         *      caches for those pages hold under-sized panels missing their caption top.
         * v14: applyGlobalSanityChecks now keeps wide-but-short banner panels (width ≥ 50% of
         *      page, height ≥ 5%) that would otherwise be dropped by the 15% height floor.
         *      Projection-based fallback in splitSinglePanelRecursively now also activates when
         *      flood-fill finds a border-edge gutter that would be rejected by the min-dimension
         *      check — previously the projection path was blocked whenever horizontalGutter was
         *      non-null even if the resulting split would have been invalid. v13 caches for
         *      pages with enclosed column gutters or wide banner panels hold wrong results.
         * v15: splitSinglePanelRecursively now also applies a projection-based fallback for
         *      vertical (column) gutters — symmetric to the existing horizontal fallback. When
         *      two side-by-side panels share an enclosed vertical gutter (flood-fill scores 0%),
         *      the fallback finds runs of ≥ 7 columns where content is < 10% of the bbox peak
         *      and splits the merged CC bbox into the correct two panels. Uses 10% (vs 20% for
         *      horizontal) to avoid splitting on sparse-content artwork columns. v14 caches for
         *      pages with enclosed side-by-side panels hold a single merged bbox.
         */
        internal const val CURRENT_SCHEMA_VERSION: Int = 15

        private val UNSAFE = Regex("[^A-Za-z0-9._-]")
    }
}

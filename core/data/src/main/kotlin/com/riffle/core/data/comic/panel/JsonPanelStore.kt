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
         * v16: splitSinglePanelRecursively now considers the wide-banner exception when deciding
         *      whether to attempt a split. Previously a split that would produce a piece shorter
         *      than 15% of the page height was always blocked — even when that piece spanned ≥
         *      50% of the page width and was ≥ 5% tall (a genuine banner panel). This matched
         *      the applyGlobalSanityChecks banner exception but the split guard was more
         *      restrictive, so banners were never separated from the splash above them on
         *      downscaled device images where the narrow gutter fell below the 15px projection
         *      band threshold and the panels merged into one bbox. v15 caches for pages with a
         *      wide banner adjacent to a splash panel hold a single merged bbox for those two.
         * v22: PanelDetector.binarize() now ORs [PanelMaskBinarizer] (local adaptive, same code
         *      as the panel mask reporter) with the global contrast classifier. The local adaptive
         *      eliminates JPEG artifact false-positives in gutter rows (the large window is
         *      dominated by adjacent dark panel content, so off-white artifact pixels satisfy
         *      v >> local_mean and are correctly classified as gutter). The global classifier
         *      catches solid-colour synthetic grids where local adaptive misclassifies interior
         *      pixels as gutter (uniform neighbourhood → local mean = pixel value → no contrast).
         *      Because the reporter uses [PanelMaskBinarizer] to generate JVM test fixtures, the
         *      OR combination ensures the fixture is a subset of the detector's content pixels —
         *      any test passing in JVM is guaranteed to produce the same or more panels on device
         *      (never fewer). v21 caches computed without local adaptive may have wrong
         *      content classifications for JPEG artifact gutter rows.
         * v21: backgroundContrastThreshold raised from 32 to 50. JPEG compression introduces
         *      artifact pixels in gutter rows at roughly v ≈ 190–210 on a 240-background page
         *      (bg − v ≈ 30–50). At threshold 32 those pixels were classified as content,
         *      contaminating gutter rows and causing the projection to classify the rows as part
         *      of a panel band instead of a gutter — merging adjacent row bands (e.g. a narrow
         *      banner with the section below it) and producing wrong panel counts. At threshold
         *      50 only genuinely dark ink (v < 190) is classified as content; the texture check
         *      catches any fine-grained panel content below this contrast level. Pre-binarised
         *      fixture masks have pixels at only DARK=20 and LIGHT=240 so JVM tests produced
         *      identical results at both thresholds — the regression only appeared with real
         *      JPEG input on device. v20 caches for pages with JPEG gutter contamination may
         *      have wrong row-band splits.
         * v20: Detection now runs at full resolution (targetLongEdge raised from 1 000 to 4 096,
         *      keeping inSampleSize=1 for typical comic pages up to ~8 190 px on the long edge).
         *      At half resolution, panel gutters shrank to ~8 px, border pixels mixed by the
         *      JPEG/PNG decoder contaminated gutter rows after binarization, and the projection
         *      fallback could not find them reliably. Running at full resolution eliminates the
         *      contamination entirely and makes JVM test fixtures (full-resolution masks) bit-for-
         *      bit identical to the algorithm input on device. All v16–v19 threshold workarounds
         *      remain in place as defence-in-depth for comics with unusually thin gutters even at
         *      full resolution. v19 and earlier caches were computed at half resolution — invalidate
         *      to re-detect at full resolution.
         * v19: effectiveHorizontalGutter now accepts a thin (≥4-row) projection gutter for
         *      banner-eligible splits. At device scale the inner white gap between the banner
         *      and the adjacent section shrinks to ~4 rows after panel borders are subtracted,
         *      falling below the 7-row general floor. The relaxation is gated on the same banner
         *      conditions (bbox ≥50% wide, short piece ≥5% page height AND ≥25% bbox height)
         *      to prevent it from firing on sparse-artwork row dips inside real panels. v18
         *      caches for pages with a banner adjacent to the bottom section hold a merged bbox.
         * v18: Banner exception threshold changed from page-relative companion (≥ 40% of page
         *      height for the tall piece) to bbox-relative short-piece check (≥ 25% of the
         *      current bbox height for the banner itself). At device scale the CC merges the
         *      banner with the adjacent bottom section into one component whose bbox is ~47% of
         *      the page; the banner occupies ~30% of that bbox while the false-split case
         *      (#757) only reached 18.8%. The old 40% companion check measured the TALL piece
         *      against the full page, which failed here because the bottom section is only 32.5%
         *      of the page — less than 40%. v17 caches for pages with a wide banner adjacent to
         *      the bottom section hold a merged bbox for those two.
         * v17: Two fixes for banner detection at device scale. (1) effectiveHorizontalGutter now
         *      accepts a thin flood-fill gutter (≥ 4px but < 7 rows) as a banner fallback when
         *      all banner conditions are met (bbox ≥ 50% wide, short piece ≥ 5%, companion ≥
         *      40%). At device scale (inSampleSize=2) the gutter between a banner and its
         *      companion panel shrinks below the 7-row projection threshold, so the gutter was
         *      previously discarded even though flood-fill already confirmed it was accessible.
         *      (2) bestGutter selection now prefers the horizontal gutter over a thicker vertical
         *      gutter when the horizontal split is banner-eligible. Without this the column gutter
         *      (spanning the full bbox height) always won the thickness comparison, splitting the
         *      bbox into ~50%-wide halves whose sub-banner pieces then failed the ≥ 50% width
         *      check and were never promoted. v16 caches with a wide banner adjacent to a splash
         *      may still hold a merged bbox if detection ran on a downscaled image.
         */
        internal const val CURRENT_SCHEMA_VERSION: Int = 22

        private val UNSAFE = Regex("[^A-Za-z0-9._-]")
    }
}

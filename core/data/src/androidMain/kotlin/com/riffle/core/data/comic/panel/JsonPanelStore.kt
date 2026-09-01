package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.PagePanels
import com.riffle.core.domain.comic.panel.PanelStore
import java.io.File
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
class JsonPanelStore constructor(
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
         * v24-v26: intermediate iterations of the #783/#784 diagonal-boundary fixes, never
         *      released — bumped commit-by-commit on the same branch. The shipped predecessor
         *      of v27 is v23.
         * v27: Diagonal-boundary repair chain reworked (issues #783, #784, #786). Both detection
         *      paths now run repairDiagonalTwoColumnRows and the new
         *      repairDiagonalAdjacentColumnPairs (widens same-row pairs cut at a diagonal
         *      boundary) BEFORE mergeDiagonalSpanningPanels, and the merge gate now requires a
         *      column-panel top (≤ 65% width) over an UNSPLIT full-width band (≥ 85% width),
         *      always emitting the band's right portion as its own panel. v26 caches can hold:
         *      a false tall merge of two stacked column panels plus a sliver remainder (#786),
         *      a diagonal-boundary panel cut at the gutter waist (#783), or a tall character
         *      split at a row boundary with the row-2 band unsplit (#784).
         * v28: mergeDiagonalSpanningPanels remainder follows the diagonal boundary (issue #787).
         *      The band remainder's left edge is now located from the band's own top/bottom
         *      strip gutter runs (capped so tall∩remainder stays under the overlap sanity
         *      threshold) instead of sitting flatly at the tall column's right edge + 1.
         *      v27 caches can hold a row-2 right panel whose left portion (the diagonal
         *      transition zone, ~130px on the reported page) is chopped off.
         * v29: Bottom-strip flood-fill fallback for vertical gutters blocked at the top by
         *      speech bubbles or artwork (issue #794). When the full-column flood-fill fraction
         *      and projection both miss a vertical boundary, the bottom 25% of the row now
         *      re-checks gutter accessibility. A top-strip guard (≥ 7-pixel run → skip) prevents
         *      firing on diagonal boundaries handled by repairDiagonalTwoColumnRows; a companion
         *      fix in repairDiagonalAdjacentColumnPairs skips diagonal widening when the
         *      bottom-strip run already sits within the actual gap (straight gutter correctly
         *      identified). v28 caches can hold a 2-panel middle row merged into one full-width
         *      panel when speech bubbles block the gutter's top-border flood-fill path.
         * v30: Diagonal profile scan for gap=0 touching panels (issue #795). When two panels
         *      physically touch (no explicit pixel gap), repairDiagonalAdjacentColumnPairs now
         *      scans the topmost flood-fill gutter pixel per column to detect gradual diagonals
         *      (< 1 row/column slope) invisible to the 45 % strip-density threshold. Height
         *      ceiling raised from 32 % to 55 % to cover half-page-tall section diagonals. An
         *      overlap cap mirrors the one in mergeDiagonalSpanningPanels to keep the resulting
         *      geometry under the global sanity-check threshold. v29 caches can hold a bottom-left
         *      panel whose right boundary follows the visible border rather than the diagonal
         *      transition zone, producing a panel ~100px narrower than the user drew.
         * v31: Diagonal-gutter fallback for split-then-merged banner CCs (issue #801). When
         *      standard projection (20% content cutoff) misses the horizontal gutter between a
         *      wide banner and a lower panel (because a diagonal boundary raises gutter-row content
         *      above the 20% threshold), a 22%-relaxed pass now fires for CCs ≥ 50% page width
         *      with bottomH < topH (banner pattern). The bannerEligible and split-guard
         *      bannerBboxMinHeightPx checks are bypassed for this fallback path. v30 caches can
         *      hold a merged panel spanning the banner area and the panel below it when a diagonal
         *      gutter separates them from a column panel that makes the CC flood-fill-inaccessible.
         * v32: Banner-exception minimum height raised from 5% to 7% in applyGlobalSanityChecks
         *      (issues #797 and #802). The v31 diagonal-gutter fallback produces a ~5.8% stub
         *      below the banner; the old 5% floor kept it as a spurious sliver panel. Raising the
         *      floor to 7% filters the stub while the 98-row gap between the banner and the
         *      full-width strip (created by the v31 split) still prevents the pre-v31 merge.
         *      v31 caches can hold a spurious sliver panel (~5.8% tall, ≥50% wide) between the
         *      banner area and the full-width strip on pages with diagonal-separated top sections.
         * v33: repairOneSidedRowJunctions gap limit raised from 12 to 15 (issue #814). Pages with
         *      a one-sided diagonal boundary gutter between 13 and 15 pixels wide were previously
         *      left with the top splash split into two half-height panels. v32 caches for such pages
         *      hold two full-width stacked panels where the correct result is a spanning left column
         *      plus two right-side pieces.
         * v34: diagonalProfileScan firstRisingIdx threshold raised from dy>0 to dy≥2 (issue #834).
         *      A horizontal gutter row just above the diagonal zone produced 1-pixel profileB bumps
         *      that anchored firstRisingIdx too early, inflating riseSpan and causing the monotonicity
         *      check to fail. Pages with a falling-right diagonal boundary where a horizontal gutter
         *      row sits above the diagonal start now have the correct wider bottom-left panel.
         * v35: Top-strip column-gap detection added to splitSinglePanelRecursively (issues #848, #849).
         *      Pages where multi-column middle rows and wide bottom panels share solid black borders
         *      with no white gutter were returned as a single merged bbox. The new mechanism scans
         *      the top 25% of a wide bbox for vertical column gaps, measures how far each gap extends
         *      downward, and either splits horizontally (gap ends mid-bbox) or vertically (gap reaches
         *      the bbox bottom). v34 caches for such pages hold fewer, over-merged panels.
         * v36: coalesceNarrowStripColumns added to both gridByProjection and CC paths (issues #876,
         *      #877). When splitSinglePanelRecursively cuts a pillar-like column into narrow vertical
         *      strips, a union bbox is formed and split horizontally at the y-gap between non-spanning
         *      members (anti-aliasing pixels in the true gutter make pixel-based detection unreliable,
         *      so the gap is derived directly from the pre-split bbox boundaries). Also fixes a
         *      walk-back overflow in repairDiagonalAdjacentColumnPairs: the loop was using the dynamic
         *      newRightMin as the right-edge proxy, so when newRightMin exceeded right.minX the proxy
         *      overlap reached zero prematurely while the actual output overlap remained large; the
         *      loop now pins to minOf(newRightMin, right.minX) to prevent the sanity-check rejection
         *      that previously forced the page onto the CC fallback path. v35 caches for pages with
         *      silhouette columns split into narrow vertical strips hold an unsplit wide panel where
         *      two stacked panels should appear.
         * v37: Dense-border horizontal split fallback added to splitSinglePanelRecursively (issues
         *      #878, #880). Pages where adjacent panels share a fully drawn ink border (no white
         *      gutter between them) could not be split by any prior fallback because all prior paths
         *      look for sparse/white rows. The new fallback scans for runs of 3–20 consecutive rows
         *      where ≥90% of the bbox width is dark content (a drawn panel-border band), then uses a
         *      post-border spike test (avg content in the 5–15 rows after the band must be <80%) to
         *      reject broad artwork zones. The minimum piece height is the banner threshold (7% of
         *      page) rather than the general threshold (14%) so the shorter strip produced by the
         *      split can survive applyGlobalSanityChecks via the banner exception. The vertical split
         *      guard for full-width short bboxes (width ≥95%, height ≤25%) now also allows a split
         *      when the chosen gutter column has <10% dark pixels across the bbox height, permitting
         *      the bottom strip from the dense-border split to be divided into side-by-side panels.
         *      v36 caches for pages with drawn ink borders between adjacent panels hold a single
         *      over-merged panel spanning both the top panel and the bottom side-by-side strips.
         */
        internal const val CURRENT_SCHEMA_VERSION: Int = 38

        private val UNSAFE = Regex("[^A-Za-z0-9._-]")
    }
}

# Panel View for Comic Book Archive

[ADR 0042](0042-comic-book-archive-cbz-support.md) shipped the [Comic Reader] as a deliberately-minimal whole-page reader: paginated only, Fit Whole hard-coded, pinch-zoom + pan + double-tap-reset live on every page, no Formatting Preferences button. Users comparing Riffle to Kindle (Panel View / Guided View) and Komga's Android client Komelia (Guided View / Guided Reading) expect a **panel-by-panel** reading mode for dense pages on small screens. We add [Panel View] as an **opt-in in-reader toggle** on top of the ADR 0042 baseline, without a Formatting Preferences panel, without altering the canonical position, and without adding a heavyweight dependency.

## Four architectural moves

### 1. Auto-detection with metadata override — not publisher-authored only

Kindle's Panel View works only on comics that were authored with region metadata (Kindle Comic Creator). That model is a non-starter for Riffle: the CBZ files a user gets from their ABS instance or LocalFiles folders have no publisher panel metadata, so a metadata-only feature would be unavailable for essentially all real content. Komelia's model — client-side auto-detection — is the only one that pays off for our audience.

Resolution order on each page:
1. If the archive contains **ACBF** with `<frame>` sequences, use them verbatim.
2. Else if the archive contains **`ComicInfo.xml`** with panel regions, use them verbatim.
3. Else run the auto-detector (below) at first entry to the page.
4. If detection produces an implausible result (0 panels, or 1 panel covering ≥95% of the page), fall back to Fit Whole for that page — Panel View stays on, that page just renders whole.

Consequences:
- No claim of perfect panel fidelity. The feature is best-effort by contract.
- Layering ACBF / `ComicInfo.xml` on top of auto-detect is purely additive; the day someone drops in a properly-authored file, we honour it for free.

### 2. Hand-rolled flood-fill-on-connected-gutter detector, no OpenCV

OpenCV would trivialize the algorithm but adds ~7-8 MB per ABI to the APK for a single opt-in feature that fails on a known subset of pages regardless of algorithm quality (heavy-black-background pages, no-gutter overlapping-frame panels). The ratio is wrong.

The algorithm — modeled on Kumiko's approach — treats the gutter as a **connected network** and finds panels as the regions the gutter walls off. Whitespace-projection was considered and rejected as v1's algorithm: it handles strict grid layouts but fails on terraces, T-shapes, and any layout where a gutter doesn't span the full page width, and patching it converges to this algorithm anyway.

Steps, per page:

1. **Preprocess.** Downscale to ~1000 px on the long edge (small enough to be cheap, large enough that thin gutter borders don't disappear). Convert to greyscale. Sample the four corners and the middle of each edge to detect the page background; invert if the majority background is dark. From here on: **light = gutter, dark = content**. Threshold (adaptive block-mean or Otsu on the downscaled bitmap) → binary mask.
2. **Trim outer margin.** Scan inward from each edge until a row/column with ≥ N content pixels is hit; crop the mask to that content bounding box. Removes page borders, scanner halos, and asymmetric bleed.
3. **Flood-fill the connected gutter.** Seed a scanline flood-fill from every gutter-valued pixel on the border of the cropped mask. Everything reached is the *connected gutter network* — the space between panels.
4. **Panels = the trapped regions.** The non-gutter pixels form connected components (islands the flood-fill can't reach because gutter walls them off). 4- or 8-connectivity CC on a binary mask, standard implementation. Each island's bounding box is a candidate panel.
5. **Filter and tighten.** Reject candidates whose bbox area is < ~2–3% of the cropped page area (speech balloons, dust). Merge two candidates whose bboxes overlap heavily or are separated only by a single-pixel gutter (a panel bisected by an internal whitespace region — e.g., sky — shouldn't split into two). Tighten each surviving bbox by trimming trailing gutter pixels.
6. **Sanity check.** Fall back to Fit Whole for this page if any of: 0 panels; 1 panel covering ≥95% of the page (a splash); any two panels overlap by more than 25% (detector is confused).

Runs off the main thread. Target: ~150 ms per page including bitmap decode on a 2018-era mid-range phone. Correctly handles Western grid, terrace, T-shape, and staircase layouts; degrades gracefully on splashes and heavy-black backgrounds.

Documented failure modes (not fixed in v1; the escape hatch from §4 handles them):
- **True dark-background pages with dark art** — thresholding produces noise; sanity check triggers fallback.
- **No-gutter panels bounded only by a thin frame line touching neighbours** — flood-fill leaks between them and they merge into one panel. Uncommon in Western comics, common in some indies.
- **Non-rectangular panels** (circles, parallelograms) — bounding box includes some gutter; framing is loose but usable.
- **Alpha-blended bleed between overlapping panels** — same leak problem as no-gutter frames.

Alternatives rejected:
- **Whitespace-projection.** Simpler, but only handles strict rectangular grids; fails on the T-shapes and terraces that make up much of real comic layout.
- **OpenCV.** See APK-weight argument above.
- **TFLite / ML Kit.** No shipping general-purpose panel model; not a real option in v1.
- **A native `.so` panel-detector via JNI.** Same weight problem as OpenCV, without the ecosystem.

### 3. Lazy per-page detection with 2-page prefetch, cached to disk

Detection runs **on first entry to Panel View for a given book**, synchronously for the current page (spinner overlay), then a coroutine prefetches page + 1 and page + 2. Subsequent panel navigation triggers the next prefetch. Results are cached to disk at `<privateStorage>/comic-panels/<bookId>/<archiveHash>.json` as `{pageIndex, imageWidth, imageHeight, panels: [{x, y, w, h}], detectionSource}` per page. Cache is invalidated by an `archiveHash` change (matches how ADR 0042 already handles re-packed archives for page count).

Alternatives rejected:
- **Eager detection at Add-to-Library** — makes adding a 200-page comic take 30+ seconds, burns CPU on books the user may never open in Panel View, hostile to the LocalFiles add flow.
- **Background WorkManager job** — introduces state (partial detection, restart-on-reboot, battery concerns) for a marginal UX gain over lazy + prefetch.

### 4. Panel index is per-device transient — canonical stays integer page

ADR 0042 pinned the comic canonical position as a zero-based integer page index and explicitly declared pan / zoom "per-device transient — not part of the canonical, not synced, not persisted across sessions." Panel index is the systematic version of pan / zoom (the reader is doing the framing instead of the user) and is treated the same way:

- **Canonical remains integer page index.** No wire-format change. No amendment to [ADR 0042]'s canonical.
- **Not synced across devices.** A user on their phone at page 47 panel 3 opens the same book on a tablet at page 47 in whole-page mode. Both agree on page 47.
- **Persisted locally**, so same-device resume lands on the last panel of the last page. If [Progress Sync] pulls a newer page from a peer, the local panel index is discarded and the reader lands on panel 0 of the incoming page. Remote wins the page; local panel state is discarded.
- **Panel View toggle state** obeys the same per-device rules.

Alternative rejected:
- **Encode panel as fractional page (`47.6` = panel 3 of 5)** — re-opens the fraction-vs-integer wire debate ADR 0042 closed, and silently lies to peers whose detector disagrees on panel counts for that page.

## Scope of the v1 Panel View

Entry:
- **TopAppBar toggle**, icon-only, per-book state, remembered per-device. Global default: off. No entry in a Formatting Preferences panel (there still isn't one for comics).

Gestures (Panel View on):
- **Tap-right / swipe-left / Vol-Down** → next panel; at last panel of page, animates page-cross to first panel of next page.
- **Tap-left / swipe-right / Vol-Up** → previous panel, symmetrical.
- **Tap-middle** → toggle [Immersive Mode] (unchanged).
- **Long-press** → whole-page peek overlay; release restores the current panel; overlay carries a **"Skip guided panels on this page"** tap-target that jumps to the first panel of the next page.
- **Pinch-zoom / drag-to-pan / double-tap-reset** — **disabled** while Panel View is on. If the automatic framing is wrong, the fix is a better detection, not a manual override.
- **Bottom scrubber** — remains page-granularity; dragging jumps to a page and lands on its first panel.

Transitions:
- **Within a page**: animated Matrix interpolation from the current panel's fitted bounds to the next panel's fitted bounds. ~250 ms, ease-in-out. Single loaded bitmap, no reload during the animation.
- **Across a page boundary**: three-stage chain — (a) zoom-out to Fit Whole on the current page, (b) horizontal page-slide, (c) zoom-in to the first panel of the new page. ~600 ms total. Deliberately not fused into one motion (that path involves bitmap crossfading during Matrix interpolation and blocks on the next image decode).
- **Reduce Motion**: honour the OS accessibility setting — all transitions collapse to instant cuts.
- **Interrupt semantics**: tapping mid-animation cancels the running animation and starts the next one from the current visual state (no queueing).

Reading order:
- Source-provided when the archive supplies it (ACBF, ComicInfo.xml).
- Otherwise **row-band heuristic**: cluster panels into horizontal rows by y-overlap, sort rows top-to-bottom, sort panels within a row left-to-right.
- **RTL manga** stays deferred per [ADR 0042]. When it lands, the within-row sort flips; row order is unchanged.
- **Escape hatch** for a mis-ordered page: the long-press peek overlay carries a "skip guided panels on this page" action.

Rotation:
- Panel View survives rotation. Panel index and toggle state are preserved; the camera re-fits the current panel to the new viewport with no animation (the OS rotation animation is already playing).
- Two-page spread in landscape stays deferred per [ADR 0042]; not opened here.

Format applicability:
- **CBZ** in v1. **CBR** picks up Panel View automatically when the RAR format lands (same page-image pipeline). **PDF** is out of scope — Pdfium owns its own zoom/pan; grafting Panel View onto that renderer is a separate project.

Untouched behaviour:
- [Progress Sync], [Reading Session], [Reading Statistics] tick on page-index deltas as today. Panel steps within a page do not tick anything.
- [Volume Key Navigation]'s Invert preference applies to panel steps identically to how it applies to page turns.
- Whole-page mode remains the default for every book on first open; nothing about ADR 0042's baseline interaction changes when Panel View is off.

## Amendments to existing ADRs and glossary

- **[ADR 0042]** (Comic Book Archive):
  - The "hard-coded Fit Whole" and "no Formatting Preferences button" statements stand for whole-page mode. Panel View is a mode toggle, not a Formatting Preference; it does not open the door to a comic Formatting Preferences panel.
  - "Pinch-to-zoom + pan + double-tap-to-reset, always live" holds in whole-page mode; **disabled while Panel View is on**.
  - Per-device transient state grows one item: `panelIndex`. Same rules as pan/zoom.
- **[Comic Book Archive]** glossary entry: cross-linked to [Panel View].
- **[Panel View]** glossary entry added.

## Deferred, on the roadmap

- **Panel View for CBR** — automatic when CBR ships; no separate work.
- **Panel View for PDF** — separate project against Pdfium.
- **RTL manga panel order** — moves with the rest of [ADR 0042]'s RTL work.
- **Two-page-spread interaction in landscape** — moves with [ADR 0042]'s spread work; will need to define whether the guide walks across a spread as one unit.
- **Publisher-authored panel metadata via a Riffle-native format** — no plan; ACBF and ComicInfo.xml cover the realistic cases.
- **Manual per-page panel reorder UI** — not shipping; the long-press "skip guided panels on this page" escape hatch handles the failure mode acceptably.
- **Cross-device panel resume** — explicitly rejected as a category error (different detectors may disagree on panel counts for the same page).

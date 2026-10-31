---
name: reference_continuous_annotation_mark_anchored_landing
description: "Continuous mode's within-chapter annotation landing was inaccurate; fix anchors against the <mark data-riffle-ann=\"<id>\"> decoration's getBoundingClientRect at every remeasure, with progression × slot.height as cold-start fallback"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 877fc3aa-a36b-4c4e-b4d7-cc76f6989506
---

Continuous reader's landing for openAtCfi (library annotation tap) was inaccurate within the target chapter — even when the right chapter loaded, the scrollY landed at the wrong row (e.g. "Section 1.2" instead of "Section 1.3") or past the rendered text into trailing whitespace (blank page). Vertical mode worked because Readium's `R2EpubPageFragment.loadLocator` priority is `text.highlight` → `fragments[0]` (`scrollToId` → `h2.getBoundingClientRect().top + window.scrollY`) → `progression`. The `cfiStringToLocator` already attaches `fragments=["s3"]` from `extractAnchorFromCfi` on the range CFI, so vertical's `scrollToId("s3")` was self-correcting and atomic. Continuous's matching path `slot.top + anchorOffsetTopDevicePx("s3")` runs an async JS rect query that fires inside `onHeightMeasured` (first measured height — pre-reflow), so it captures the pre-reflow Y. Re-firing on every target remeasure helps but isn't enough (height can stabilise while sub-pixel layout still shifts; also the reapply only watches the target's measuredPx, not behind-buffer chapters).

**Fix:** anchor the landing against the **annotation's own DOM decoration** (`<mark data-riffle-ann="<id>">`) instead of a heading id. That mark is injected by `ContinuousStyleInjector.applyAnnotationHighlightsJs` which runs from `ChapterWebView.onPageFinished` AFTER `injectStylesAndMeasure` triggers the post-style reflow — so when the mark is found its rect is post-reflow accurate. Plumbing:

1. **`AnnotationDao.getByItemAndCfi(serverId, itemId, cfi): AnnotationEntity?`** + `AnnotationStore.findByItemAndCfi(...)` exposure (one-shot lookup).
2. **`EpubReaderViewModel.openBook`**: look up annotation by `openAtCfi`, stash its id on `ReaderState.Ready(initialFocusAnnotationId: String?)`.
3. **`ChapterWebView.annotationOffsetTopDevicePx(id, callback)`**: sibling of `anchorOffsetTopDevicePx` — `querySelector('[data-riffle-ann="<id>"]').getBoundingClientRect().top * dpr`.
4. **`ContinuousReaderView.initialize(focusAnnotationId)` → `openWindowAt(focusAnnotationId)`**: in `pendingInitialScroll`, try `annotationOffsetTopDevicePx` first, fall back to `anchorOffsetTopDevicePx(anchorFragment)`, then `progression × slot.height`. The existing reflow-tracking re-land (every target remeasure) re-fires the closure; once the mark exists, subsequent fires snap precisely.
5. **`EpubReaderScreen`**: pass `state.initialFocusAnnotationId.takeIf { latestLocator() == null }` so the id only applies on the very first open (not on rotation when latestLocator supersedes).

**Critical timing claim:** `applyAnnotationHighlightsJs` is queued in `onPageFinished` BEFORE the first `HEIGHT_MEASUREMENT_JS` `report()` runs, and WebView serializes JS on the WebCore thread — so by the time `pendingInitialScroll` fires inside `onHeightMeasured` the mark IS in DOM. If not (cold-start race), the closure falls through to progression and the next remeasure (which fires when injectStylesAndMeasure reflow grows the chapter) hits the mark and re-lands precisely.

**Branch:** pkmetski/continuous-annotation-nav-focus. **Related:** see [[reference_continuous_annotation_focus_reflow_race]] for the earlier reflow-tracking re-land that this builds on; [[reference_continuous_empty_pages_scrollheight_clamp]] for the body-bottom measurement that makes slot.height equal real content height.

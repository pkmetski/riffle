---
name: reference-continuous-cold-open-latency
description: "Continuous-mode \"book takes 5-8s to open\" root cause (all 3 window chapters loaded at once + measured only at the load event after image decode) and the fix shape (target-only gate, deferred neighbours, DOM-ready measure with reserved image boxes)"
metadata:
  node_type: memory
  type: reference
  originSessionId: 803bec8c-467d-4bed-ae5b-08d69181869c
  modified: 2026-09-25T18:00:05.700Z
---

**Symptom (2026-09-25):** continuous mode, image-heavy O'Reilly EPUB (32 MB, 163 PNGs, 150–200 KB chapters), no annotations: 5–8 s spinner on the user's Samsung; 4.3 s reproduced on a headless API-37 AVD.

**Root cause (measured with a `+ms` mark on every phase):**
- Pre-window work is ~0.6–1.0 s (server progress pull ~300 ms on the critical path, Readium open, sync cycle on Main) — not the problem.
- `ContinuousWindowController.openWindowAt` loaded all 3 window chapters at once and the initial-land gate (`InitialWindow.pendingMeasureIndices`, PR #582) waited for EVERY one. The three WebViews share one renderer; the target chapter routinely parsed LAST and the 2.5 s fallback fired before it had reported page-finished.
- Each chapter was styled + measured only in `onPageFinished` (load event), which waits for every image to decode: DOM-ready was 0.1–0.6 s, load 0.5–1.3 s on the fast AVD.
- Resource reads via `shouldInterceptRequest` are negligible (58 requests, 0.3 s total, off-main). Readium `positionsByReadingOrder` is ~25 ms. Not the O'Reilly lazy path (that source has its own paced fetcher; check `cache/oreilly_lazy` to rule it in/out).

**Fix shape (branch pkmetski/nashville-v1):** gate on the target only; append neighbours as placeholder slots and load them one at a time after reveal (ahead first); compensate the behind-neighbour's first measure on `doOnNextLayout` (prepend-style, else NestedScrollView clips the scrollBy) and arm the backward scroll floor while it is unmeasured; measure at DOMContentLoaded when every `<img>` has width/height, reserving each undecoded image's box inline (attr width → CSS-clamped resolved width → attr aspect height, released on load) because ReadiumCSS `width:auto;height:auto` discards the attribute size. Result on AVD: window phase 1.1–1.7 s → ~0.25 s, reveal lands once with the exact final height.

**Gotcha:** `document.fonts.status` reads 'loaded' at DOMContentLoaded until a layout has been forced — read `offsetHeight` before deciding to wait on `fonts.ready`.

Related: [[reference_continuous_annotation_focus_reflow_race]], [[reference_continuous_webview_16384_raster_cap]].

---
name: panel-splash-split-pan
description: "Single-panel page 'senseless pan' = overflow SPLIT slicing a full-page splash panel into zoom-gain-zero slices; NOT an animation bug"
metadata: 
  node_type: memory
  type: reference
  originSessionId: e49a93ad-7172-4314-85b1-fc454203a684
  modified: 2026-08-31T06:58:43.846Z
---

CBZ Panel View bug (2026-08-31, branch pkmetski/single-panel-pan-jitter): user reported "weird panning down and up" on single-panel pages. Two fix attempts targeted the *animation* (animateFloatAsState → Animatable re-key, spinner while pagePanels==null) and failed because the movement was a real focus change, not an animation artifact.

Root cause: `PanelOverflowTransform.isOverflowing` used `panelDisplayW < viewportHeight`, which is ~always true in portrait, so the check degenerated to `widthRatio >= 0.85` — catching full-page splash panels, not just banners. The splash panel got split top/bottom, but each slice's PanelFitTransform zoom stayed width-bound (≈1.0), so "next panel" panned the same full-width page vertically with black voids swapping sides — a pure pan with zero zoom gain.

Fix: isOverflowing now requires the half-split to deliver real zoom gain (`zoomHalf/zoomWhole >= 1.5`), with split direction matching applyOverflow's isWide choice. Splash pages (gain 1.0) stay whole; true banners (gain 2.0) still split. Pinned in PanelOverflowTransformTest with both-sides boundary tests (heights 1200/1350 around the vpH/1.5 boundary).

**Lesson:** when a user reports motion in the reader, first establish *what state transition* moves the camera (frame-by-frame ffmpeg on their recording — [[feedback-video-frames-via-ffmpeg]]), before touching animation plumbing. Static screenshots seconds after a tap prove nothing. Related: [[panel-detector-path-invariant]].

Follow-up fixes on the same branch:
- Max-gain split direction: splash panels split LEFT/RIGHT on 20:9 screens (gain 1.57 ≥ 1.5, real magnification), stay whole on 16:9; direction shared with the energy sampler via two-arg `(panel, splitHorizontally)` signature.
- SMART_SPLIT seam gate: `effectivePanels` holds (null → spinner) while the seam decode is Pending for pages that would split, via `EnergySamplerState` + `shouldHoldForSmartSeam` — no center→energy re-split jump.
- **Stale-bitmap zoom flash**: `produceState` in CbzPanelViewer keeps the PREVIOUS page's bitmap while the new page decodes; the new page's panels resolve faster (prefetched), so the old image rendered ~250ms through the NEW page's panel transform on every page change ("unnecessary zoom just before changing page"). Fix = `CbzPageDecodeState.forPage` + `decodeForPage()` gate treating another page's decode as loading. Pinned in StalePageDecodeTest.

---
name: reference_toc_nav_reflow_race
description: "TOC/search \"lands a page before/after the target\" = snap fired before the post-load typography reflow; root cause + fix"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 17e7ac48-43ae-47b1-abd5-decf807650c5
---

The "TOC navigation is hit-and-miss / lands the page before or after the expected chapter" bug (book 00c67042 = *Lean Customer Development*, big O'Reilly chapters with mid-chapter section fragments like `ch06.html#creating_a_summary`).

Root cause, reproduced on-device via WebView DevTools (CDP eval, see [[reference_api25_avd_boot_incantation]]): on a cross-resource jump Readium `go()`s to the target, then `onPageLoaded` injects the typography override which **reflows the new chapter asynchronously** — growing `scrollWidth` and shifting the target into a *different column* a few hundred ms later (measured: scrollWidth 14280→14688 (+1 col) at ~600ms; heading moved col 7→col 8). The old fix (#103) snapped a fixed `NAV_COVER_SETTLE_MS=100ms` after `go()` using `SNAP_NEAREST_COLUMN_JS` = `round(scrollLeft/iw)*iw`, which (a) fires BEFORE the reflow and (b) only rounds the current scroll, so it locks onto the pre-reflow column and never re-corrects → page rests one-or-more columns off the target. Intermittent because it races the reflow; worse on big chapters. NOT a round-vs-floor bug: every heading sits exactly 20px (body margin) into its column, so round==floor when settled.

Fix (PR on branch pkmetski/manama): replaced `SNAP_NEAREST_COLUMN_JS` with `snapToTargetColumnJs(fragmentId)` — a rAF loop that every frame re-locates the target by its href-fragment id and FLOORS scrollLeft to the column it currently occupies (tracking it as reflow moves it), stopping only once `scrollWidth` holds steady for a few frames or a ~1.2s cap. Element-anchored (like the figure-tap `scrollToColumnJs`), so robust to where `go()` landed and to reflow timing. Bare-href (no fragment) → floor to col 0. The nav cover is now purely cosmetic. Verified live that element-anchored floor lands col 7→col 8 (onPage=true); full live re-run of the race with the new build was blocked by [[reference_harness_avd_contention_flake]] (emulator killed within seconds repeatedly).

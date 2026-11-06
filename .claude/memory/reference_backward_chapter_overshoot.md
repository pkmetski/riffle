---
name: reference-backward-chapter-overshoot
description: "paginated prev-chapter swipe overshooting 4-5 pages = Readium's own ViewPager turn + onPageLoaded typography reflow; fixed in onPageLoaded via landedAtEnd→snapToEnd, NOT in onNavigateBackward"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 18bcb4a8-f7ce-4706-a9a4-6b8f5c8c09f6
---

"On chapter N, swiping back to the previous chapter lands 4-5 pages short of the end instead of on its last page" (The Martian ch17→ch16 repro, device-verified).

KEY TRAP: in PAGINATED mode the backward cross-resource swipe is handled entirely by Readium's own R2WebView/ViewPager — `ScrollBoundaryNavigationContainer` and its `onNavigateBackward`/boundary-poll are gated to scroll/Vertical mode only (`dispatchTouchEvent`/`onInterceptTouchEvent` early-return when `!isScrollMode`; wired at EpubReaderScreen `container.isScrollMode = orientation==Vertical`). So fixing `onNavigateBackward` does NOTHING for the reported (paginated) bug — confirmed by instrumenting: the handler never fires.

Real mechanism: Readium positions the previous resource at its END against the BASE width, then `onPageLoaded`'s typography/readaloud-reserve injection reflows (widens) the page, so the end position becomes a MIDDLE column; the at-rest backstop only rounds to the NEAREST column, leaving it stranded mid-chapter. Same reflow-race family as [[reference_toc_nav_reflow_race]] but on Readium's swipe path, not a go().

Fix (all in `onPageLoaded`, EpubReaderScreen + ColumnSnap): at page load, BEFORE typography injection, read `ColumnSnap.landedAtEnd(fragment)` (LANDED_AT_END_JS: paginated && scrollLeft+iw >= scrollWidth-4). If true (a backward landing — forward turns land at col 0, TOC at chapter top → false), after injection call `ColumnSnap.snapToEnd` → `snapToEndColumnJs()` rAF loop that re-pins to the last column each frame until scrollWidth stabilises. Regression: `BackwardChapterSnapJsTest` (real WebView). Device-verified: back swipe ch17→ch16 lands on ch16 last page; forward ch16→ch17 lands at ch17 start; TOC→ch12 lands at ch12 start. NB chapters are split resources (part0022_split_000.html). Scroll-mode backward overshoot NOT investigated (separate; onNavigateBackward left as raw go()).

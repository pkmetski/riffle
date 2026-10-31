---
name: reference-harness-ui-tap-gotchas
description: "Harness taps that silently no-op — ribbon under floating TopAppBar, continuous INVISIBLE container, detail-screen async reflow under Read button"
metadata: 
  node_type: memory
  type: reference
  originSessionId: c6c3ebb6-0842-4b10-89f3-916a31f30056
  modified: 2026-08-11T11:34:55.514Z
---

Three recurring "performClick succeeded but nothing happened" causes in the Riffle harness (all bit during the 2026-08 CI false-green cleanup, see [[reference-ci-false-green-2026-08]]):

1. **Corner bookmark ribbon sits UNDER the floating TopAppBar.** The bar overlays the reader without resizing it; the ribbon (TopEnd, 24×32dp) is inside the bar's ~64dp band. With chrome visible, a semantics click on "Bookmark this page" lands on the bar surface and `toggleBookmark` never fires — deterministically. Bookmark-creation flows must hide chrome first (`hideTopAppBar()` helper in AnnotationFocusHarnessTest).

2. **Continuous mode mounts TAG_READER_READY while the container is still INVISIBLE** (openWindowAt holds visibility until the initial landing reveals; the reveal can lag seconds under WebView measure storms). Touches dispatched before the reveal find no touch target → gestures silently no-op (backwardNavigationIntent never arms). Barrier: wait for `reader.isFirstLoadComplete.value` (or TAG_LOADING spinner gone) after reader-ready AND after every out-of-window `navigateTo`.

3. **Detail screen reflows asynchronously under the Read button** (reading-time estimate / PDF page count row lands 1–3s after first render). Production fix: `publicationFactsLineReservesSpace` keeps the row's slot from the first frame. Harness fix: `tapReadInDetailScreen` is click-and-verify (re-click if the detail screen is still up after 2s).

Related production bug found the same way: `ContinuousWindowController.navigateTo` cleared `backwardFlingFloorY` but not `boundaryDetentArmed`, so any in-window programmatic nav (TOC/bookmark jump) after a backward fling got clamped to the chapter boundary instead of the requested progression. Detent is gesture-scoped; programmatic nav must clear it.

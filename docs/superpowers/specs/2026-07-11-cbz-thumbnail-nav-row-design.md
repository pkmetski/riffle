# CBZ thumbnail navigation row — design

## Problem

The CBZ reader's bottom navigation row is a Material `Slider` (`CbzScrubber` in `app/src/main/kotlin/com/riffle/app/feature/reader/cbz/CbzReaderScreen.kt`). It gives a linear seek position but no visual context for where you are in the comic. Users want a scrollable thumbnail strip instead — the same "slide across pages" affordance a physical comic gives you when you fan the edges.

Slide-to-turn on the page canvas already works (the reader uses `HorizontalPager`). This change is only about the nav row.

## Scope

- Replace `CbzScrubber` with `CbzThumbnailStrip`, a horizontally-scrollable `LazyRow` of page thumbnails.
- Tap a thumbnail → jump to that page (existing `viewModel.jumpToPage`).
- Current page is visually marked; strip auto-centers on the current page as it changes.
- Page swipe on the canvas is unchanged.

Out of scope: dragging a finger across the strip as a live scrubber, long-press previews, pinch-zoom of the strip, chapter markers, preloading all thumbnails eagerly.

## Design

### Component: `CbzThumbnailStrip`

- `LazyRow` with one item per page, `contentPadding = 12dp horizontal`, `spacedBy(4dp)`.
- Each item ≈ 40dp × 56dp (comic ~2:3 aspect).
- Current page: 2dp border in `MaterialTheme.colorScheme.primary`.
- A "N / total" label sits above the row (kept from today's UI) so page count remains readable at a glance.
- Container: ~72dp tall (thumb + label + padding), same `surface.copy(alpha = 0.9f)` background as today so it reads over dark comic pages.
- Tap → `onSeek(pageIndex)` → `viewModel.jumpToPage(pageIndex)`.
- `LaunchedEffect(currentPage) { listState.animateScrollToItem(currentPage, scrollOffset = -centeringOffsetPx) }` keeps the current thumb centered when the page changes via swipe / volume keys / resume.

### Thumbnail decoding

- Reuse `CbzImageSource.imageBytes(pageIndex)` — the existing decode path used by `CbzPage`.
- Load via Coil `AsyncImage` per item; Coil's memory + disk caches absorb repeat scrolls.
- Downsample with `ImageRequest.size(Size(120, 168))` so 100+ pages don't hold full-res bitmaps.
- Placeholder while decoding: flat dark box, no spinner (spinners across dozens of tiles are visually noisy).
- Off-screen items are recycled by `LazyRow`; there is no separate preload pass.

### ViewModel

No changes. `currentPage` flow and `jumpToPage(Int)` already cover reads and writes.

### Test tags

- `LazyRow` → `cbz_thumbnail_strip` (replaces today's `cbz_scrubber`).
- Each item → `cbz_thumb_$index`.

## Testing

Compose UI + Coil don't unit-test cleanly at the JVM level, so verification is instrumentation:

- New `CbzReaderScreenTest` under `app/src/androidTest/kotlin/com/riffle/app/feature/reader/cbz/`:
  - Launches `CbzReaderScreen` with a fake `CbzImageSource` returning solid-color bitmaps for N pages.
  - Reveals the nav row (screen starts non-immersive) and clicks `cbz_thumb_5`.
  - Asserts the pager settles on page 5 (assert on `cbz_pager` current page via a semantics probe, or via a page-index label rendered in the fake).

Test runs via `make harness-test`. This is the regression assertion for the swap: if someone reverts to the `Slider`, the test fails because `cbz_thumb_5` no longer exists.

## Files touched

- `app/src/main/kotlin/com/riffle/app/feature/reader/cbz/CbzReaderScreen.kt` — replace `CbzScrubber` with `CbzThumbnailStrip`; remove `Slider` import.
- `app/src/androidTest/kotlin/com/riffle/app/feature/reader/cbz/CbzReaderScreenTest.kt` — new file.

## Non-goals / decisions locked in

- Not adding drag-scrub semantics — Option A only (tap-to-jump, free scroll of the strip).
- Keeping the "N / total" label above the strip.
- Thumbnail size is 40dp × 56dp; page numbers under each thumb are not shown (label above is enough).

# 8 — Library Cover Grid (iOS)

## 8.1 Adaptive grid uses more columns on Expanded width class (tablet)

**Given** the device window is in the Expanded (tablet) width class.
**When** the library cover grid is rendered.
**Then** the grid shows ≥ 4 columns (wider min-cell-size than the phone fallback).

**Coverage:** `AdaptiveCoverGridTest.expandedClassYieldsMoreThanThreeColumns` — tablet AVD only; iOS equivalent is verified manually via Xcode build on an iPad simulator.

**iOS gap:** No KMP type to assert; this is a pure SwiftUI layout constraint. Documented as UI-only.

## 8.2 SeeMore tile appears when items exceed two-row capacity

**Given** a library section contains more items than can fit in two rows.
**When** `BookSectionGrid` (or equivalent iOS view) renders.
**Then** a "See more" (or "+N") tile appears as the last item in row 2.

**Coverage:** `BookSectionGridTest.seeMoreAppearsWhenItemsExceedTwoRowCapacity`

## 8.3 SeeMore tile absent for a single item

**Given** a library section contains exactly one item.
**When** the section grid renders.
**Then** no "See more" tile appears.

**Coverage:** `BookSectionGridTest.seeMoreAbsentWhenItemsCountIsOne`

## 8.4 All items shown when onSeeMore is null (no truncation)

**Given** the grid is configured with no "See more" callback.
**When** the grid renders.
**Then** all items are visible and no "See more" tile appears.

**Coverage:** `BookSectionGridTest.seeMoreAbsentAndAllItemsShownWhenCallbackIsNull`

## 8.5 SeeMore tile shares its row with at least one cover tile

**Given** the grid has more items than a two-row capacity.
**When** the grid renders.
**Then** the SeeMore tile appears on the same row as at least one cover tile (never orphaned on its own row).

**Coverage:** `BookSectionGridTest.seeMoreTileSharesRowWithAtLeastOneCoverTile`

## 8.6 SeeMore tile displays the correct overflow count

**Given** the grid has more items than the two-row preview capacity.
**When** the grid renders.
**Then** the tile label shows "+N" where N is the number of hidden items.

**Coverage:** `BookSectionGridTest.seeMoreDisplaysCorrectOverflowCount`

## 8.7 Readaloud icon appears on cover tile when linked

**Given** a library item has a linked readaloud (synced narration).
**When** the cover tile renders.
**Then** a readaloud badge icon is visible on the tile.

**Coverage:** `BookCoverTileReadaloudTest.shows_readaloud_icon_when_linked`

## 8.8 No readaloud icon when not linked

**Given** a library item has no linked readaloud.
**When** the cover tile renders.
**Then** no readaloud badge icon is visible.

**Coverage:** `BookCoverTileReadaloudTest.no_icon_when_not_linked`

## 8.9 Series position badge shown on series detail grid

**Given** a series item has a position string (e.g. "The Expanse #4").
**When** the series detail grid renders.
**Then** a compact badge showing the position number (e.g. "#4") is visible on the cover tile.

**Coverage:** `SeriesDetailGridTest.coverShowsCompactSeriesPositionBadge`

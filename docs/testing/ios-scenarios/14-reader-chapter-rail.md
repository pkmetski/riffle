# 14 — Reader Chapter Rail (iOS)

## 14.1 Chapter rail renders at 4dp height

**Given** the chapter navigation rail is rendered.
**Then** its height is exactly 4dp (the progress pill at the bottom of the reader screen).

**Coverage:** `ChapterRailIsolationTest.groupedRailRendersFourDpProgressWithParentChapterColors`

## 14.2 Grouped rail shows parent-chapter palette colors (colored chapter map enabled)

**Given** the chapter rail has multiple sections grouped into parent chapters.
**When** "Colored chapter map" is enabled in settings.
**Then** active chapter sections render in the chapter's distinctive colour; unread sibling sections render a muted version of the same colour.

**Coverage:** `ChapterRailIsolationTest.groupedRailRendersFourDpProgressWithParentChapterColors`

**iOS gap:** Pixel-colour verification is not feasible in XCTest without a full app binary snapshot. Verified manually via Xcode build (visual inspection on device).

## 14.3 Grouped rail shows neutral colors when colored chapter map is disabled

**Given** "Colored chapter map" is disabled in settings.
**When** the chapter rail renders.
**Then** no parent-chapter palette colours appear; all segments use a neutral grey.

**Coverage:** `ChapterRailIsolationTest.groupedRailRendersNeutralProgressWhenColorsAreDisabled`

## 14.4 Cursor position update recomposes only the rail, not sibling views

**Given** the chapter rail is displayed alongside an EPUB navigator view.
**When** the reading position (cursor) changes.
**Then** the rail overlay recomposes but the sibling EPUB view does not recompose.

**Coverage:** `ChapterRailIsolationTest.cursorPositionChangeRecomposesOnlyRailNotSibling`, `ChapterRailIsolationTest.siblingDoesNotRecomposeWhenRailCursorUpdatesRepeatedly`

**iOS gap:** Recomposition isolation is a Compose-specific concern. On iOS / SwiftUI the equivalent is that updating the progress binding does not trigger a full view redraw of the EPUB navigator. Verified manually via Instruments (view body call counts).

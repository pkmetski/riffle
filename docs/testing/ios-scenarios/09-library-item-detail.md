# 9 — Library Item Detail (iOS)

## 9.1 Read button does not move when reading-time estimate arrives

**Given** the detail screen renders with no estimated reading time.
**When** the reading-time estimate resolves asynchronously (e.g. "2h 6m estimated" arrives).
**Then** the Read button position does not change — no layout shift that would swallow in-flight taps.

**Coverage:** `LibraryItemDetailReadButtonStabilityTest.readButtonDoesNotMoveWhenReadingTimeEstimateArrives`

**iOS gap:** Pure SwiftUI layout; verified manually via Xcode build.

## 9.2 Tablet layout renders two panes

**Given** the device is in Expanded width class (tablet).
**When** the library item detail screen renders.
**Then** both the left pane (cover + metadata) and the right pane (description / summary) are visible.

**Coverage:** `LibraryItemDetailTabletLayoutTest.bothPanesRenderAndScrollingRightPaneDoesNotMoveLeft` — tablet only.

**iOS gap:** UI-only, verified manually on iPad.

## 9.3 Scrolling right pane does not move the left pane (tablet)

**Given** the detail screen is in two-pane tablet layout.
**When** the user scrolls the right pane upward.
**Then** the left pane and title remain at their original positions.

**Coverage:** `LibraryItemDetailTabletLayoutTest.bothPanesRenderAndScrollingRightPaneDoesNotMoveLeft`

## 9.4 Search field is not auto-focused on library entry

**Given** the library screen loads.
**When** the library search header renders.
**Then** the search text field does not have focus (keyboard not popped, no cursor blinking on entry).

**Coverage:** `LibrarySearchHeaderFocusTest.searchFieldIsNotFocusedOnEntry`

**iOS gap:** UI-only focus behaviour; verified manually via Xcode build.

## 9.5 Clear button hidden when search query is empty

**Given** the search header renders with an empty query.
**Then** no "Clear search" button is visible.

**Coverage:** `LibrarySearchHeaderFocusTest.clearButtonIsHiddenWhenQueryIsEmpty`

## 9.6 Clear button visible when query is non-empty

**Given** the search header renders with a non-empty query.
**Then** a "Clear search" button is visible.

**Coverage:** `LibrarySearchHeaderFocusTest.clearButtonIsVisibleWhenQueryIsNonEmpty`

## 9.7 Clear button click clears the query

**Given** the search header has a non-empty query and the clear button is visible.
**When** the user taps the clear button.
**Then** the search query is set to empty string.

**Coverage:** `LibrarySearchHeaderFocusTest.clearButtonClickInvokesOnSearchQueryChangeWithEmpty`

## 9.8 Download readaloud button fires download callback

**Given** the item detail shows a readaloud download button in the "not downloaded" state.
**When** the user taps the button.
**Then** the download callback is invoked.

**Coverage:** `ReadaloudDownloadButtonTest.not_downloaded_tap_invokes_download`

## 9.9 Remove readaloud button fires remove callback

**Given** the item detail shows a readaloud download button in the "downloaded" state.
**When** the user taps the button.
**Then** the remove callback is invoked.

**Coverage:** `ReadaloudDownloadButtonTest.downloaded_tap_invokes_remove`

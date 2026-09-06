# 10 — Audiobook Player UI (iOS)

## 10.1 Publication year shown under author when present

**Given** an audiobook has a non-null published year (e.g. "2014").
**When** the player surface renders.
**Then** the year is visible below the author name.

**Coverage:** `PlayerTitleYearTest.yearShowsWhenPresent`

## 10.2 Publication year omitted when null

**Given** an audiobook has no published year (null).
**When** the player surface renders.
**Then** no year text is visible in the title block.

**Coverage:** `PlayerTitleYearTest.yearIsOmittedWhenNull`

## 10.3 Publication year omitted when blank

**Given** an audiobook has a blank published year ("").
**When** the player surface renders.
**Then** no year text is visible in the title block.

**Coverage:** `PlayerTitleYearTest.yearIsOmittedWhenBlank`

## 10.4 "Bookmark saved" snackbar appears after bookmark creation

**Given** the user adds a bookmark in the audiobook player.
**When** the bookmark is saved.
**Then** a "Bookmark saved" snackbar appears with an "Undo" action.

**Coverage:** `AudiobookPlayerSnackbarTest` — snackbar mechanics verified via Compose test; iOS equivalent is UI-only.

**iOS gap:** SwiftUI snackbar/toast requires instrumentation harness; verified manually via Xcode build.

## 10.5 "Undo" action is visible in the bookmark snackbar

**Given** the "Bookmark saved" snackbar is showing.
**Then** an "Undo" action label is visible alongside the message.

**Coverage:** `AudiobookPlayerSnackbarTest` (action label assertion).

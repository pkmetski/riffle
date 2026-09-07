# iOS Scenario: Playlists and To-Read Tabs (Issue #912)

Covers `IosPlaylistsRepositoryImpl` and `IosToReadRepositoryImpl` wired into `LibraryItemsScreen`
via the iOS Koin module, replacing the previous no-op bindings.

## Preconditions
- App configured with an ABS source that has at least one book library.
- The ABS instance has at least one user-created playlist (not named "To Read" or "To Listen").
- The ABS instance has at least one book marked as "To Read" by the current user.

## Scenarios

### 2.1 — Playlists tab shows server playlists
**Steps:**
1. Navigate to `LibraryItemsScreen` for any ABS library.
2. Tap the "Playlists" tab.

**Expected:**
- User-created playlists from ABS appear as tiles.
- The "To Read" playlist is NOT shown in this tab (it is reserved for the dedicated To-Read surface).

### 2.2 — To-Read toggle reflects server state
**Steps:**
1. Navigate to `LibraryItemsScreen`.
2. Observe the To-Read toggle on a book that is already in the "To Read" playlist on ABS.

**Expected:**
- The toggle is shown as active (filled heart / marked icon).

### 2.3 — Adding a book to To-Read persists to ABS
**Steps:**
1. Tap the To-Read toggle on a book that is NOT in "To Read".

**Expected:**
- The toggle becomes active optimistically (no loading state visible).
- Refreshing the screen still shows the toggle as active (server round-trip succeeded).

### 2.4 — Removing a book from To-Read persists to ABS
**Steps:**
1. Tap the To-Read toggle on a book that IS in "To Read".

**Expected:**
- The toggle becomes inactive optimistically.
- Refreshing the screen still shows the toggle as inactive.

### 2.5 — Non-ABS source shows empty Playlists tab
**Steps:**
1. Switch to a Komga, Chitanka, or Gutenberg source.
2. Navigate to its library and open the Playlists tab.

**Expected:**
- The Playlists tab is empty (no playlists shown) — non-ABS sources return `true` from `refresh`
  with an empty list, so the tab renders but is blank rather than erroring.

## Test coverage

Scenarios 2.1–2.5 require a live ABS instance and are verified manually on a simulator.
The reserved-name sentinels (`TO_READ_PLAYLIST_NAME`, `RESERVED_PLAYLIST_NAMES`) that both
`IosToReadRepositoryImpl` and `IosPlaylistsRepositoryImpl` filter on live in shared Kotlin
(`core/data` / `core/domain`) and are exercised by `PlaylistsRepositoryTest`
(`core/data/src/androidHostTest`). The former `PlaylistsToReadTests.swift` XCTest file only
re-asserted those Kotlin constants from Swift (constant echoes) and was removed.

# iOS Scenario: Offline Availability and Download Status (Issue #914)

Covers `IosLibraryItemOfflineAvailabilityImpl` wired into `LibraryItemsScreen` on iOS.

## Preconditions
- App is configured with an Audiobookshelf source that has a library containing books.
- App launches to `HomeScreen`.

## Scenarios

### 4.1 — Offline filter hides items with no local files

**Steps:**
1. Launch the app with a configured ABS source.
2. Wait for the library to load.
3. Toggle the offline filter (go offline or simulate offline via airplane mode).

**Expected:**
- When offline and no books are locally stored, the "All Books" / section grids are empty.
- No books appear in sections that the filter applies to.

### 4.2 — Offline filter shows items with a downloaded EPUB

**Steps:**
1. Place a valid file at `<Documents>/epub-downloads/<sourceId>/<itemId>.epub` (e.g., via Xcode Devices
   file transfer or a test helper).
2. Toggle the offline filter.

**Expected:**
- The book whose `sourceId`/`itemId` matches the file path appears in the library.
- Other books (with no local file) are hidden.

### 4.3 — Offline filter shows items with a cached EPUB

**Steps:**
1. Place a file at `<Documents>/epub-cache/<sourceId>/<itemId>.epub`.
2. Toggle the offline filter.

**Expected:**
- The book appears (cached is treated as offline-available, same as downloaded).

### 4.4 — Download badge appears on tiles with downloaded books

**Steps:**
1. Ensure a book's DB row has `isDownloaded = true` (set by a future iOS download feature).
2. Observe the library grid.

**Expected:**
- A small purple dot badge is visible on the top-right corner of the book tile.
- Books with `isCached = true` show a lighter dot badge.
- Books with neither flag show no badge.

### 4.5 — Download badge absent when book is not downloaded

**Steps:**
1. Observe a book tile where `isDownloaded = false` and `isCached = false`.

**Expected:**
- No badge is rendered on the tile.

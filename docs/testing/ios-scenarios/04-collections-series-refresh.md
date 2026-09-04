# iOS Scenario: Collections and Series Refresh (Issue #913)

Covers `IosLibraryRefresherImpl.refreshCollections` and `refreshSeries` — the real ABS API calls
and Room persistence wired by this issue.

## Preconditions
- App is configured with an Audiobookshelf source that has at least one library containing series
  or collections.
- App launches to `HomeScreen` and navigates to `LibraryItemsScreen`.

## Scenarios

### 4.1 — Series tab shows data after pull-to-refresh
**Steps:**
1. Open `LibraryItemsScreen` for a library that has series.
2. Navigate to the "Series" tab.
3. Perform a pull-to-refresh gesture.

**Expected:**
- While refreshing, a loading indicator is visible.
- After refresh completes, series tiles (name + book count) are visible.
- Each series tile shows the cover image of the first book in the series.

### 4.2 — Collections tab shows data after pull-to-refresh
**Steps:**
1. Open `LibraryItemsScreen` for a library that has collections.
2. Navigate to the "Collections" tab.
3. Perform a pull-to-refresh gesture.

**Expected:**
- While refreshing, a loading indicator is visible.
- After refresh completes, collection tiles (name + book count) are visible.

### 4.3 — Non-ABS sources return success without crashing
**Steps:**
1. Configure the app with a Komga source.
2. Navigate to a library screen.
3. Trigger a refresh.

**Expected:**
- The refresh completes without error (non-ABS sources skip series/collections silently).

### 4.4 — Series and collections persist across app restarts
**Steps:**
1. Refresh a library with series/collections (see 4.1 / 4.2).
2. Force-quit and relaunch the app.
3. Navigate back to the same library's Series / Collections tab.

**Expected:**
- Series and collection tiles are visible immediately (served from local DB, before any refresh).

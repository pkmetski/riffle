# iOS Scenario: Series and Collection Detail (Issue #916)

Covers `SeriesDetailScreen` and `CollectionDetailScreen` rendered via `HomeScreen` on iOS.

## Preconditions
- App is configured with an Audiobookshelf source that has at least one library with series or collections.
- App launches to `HomeScreen`.

## Scenarios

### 4.1 — Tapping a series tile navigates to series detail
**Steps:**
1. Launch the app with a configured ABS source.
2. Wait for the library home to load.
3. Scroll to the "Series" section.
4. Tap a series tile.

**Expected:**
- A series detail screen appears showing the series name in the top bar.
- A grid of book covers for that series is visible (or "No books in this series" if empty).

### 4.2 — Back from series detail returns to library home
**Steps:**
1. Navigate into a series detail screen (see 4.1).
2. Tap the back arrow ("←").

**Expected:**
- The library home screen reappears with the section grid.

### 4.3 — Tapping a collection tile navigates to collection detail
**Steps:**
1. Launch the app with a configured ABS source.
2. Wait for the library home to load.
3. Scroll to the "Collections" section.
4. Tap a collection tile.

**Expected:**
- A collection detail screen appears showing the collection name in the top bar.
- A grid of book covers for that collection is visible (or "No books in this collection" if empty).

### 4.4 — Back from collection detail returns to library home
**Steps:**
1. Navigate into a collection detail screen (see 4.3).
2. Tap the back arrow ("←").

**Expected:**
- The library home screen reappears with the section grid.

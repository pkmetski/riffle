# iOS Scenario: Library Item Detail Navigation (Issue #915)

Covers `LibraryItemDetailScreen` and `LibraryItemDetailViewModel` in `commonMain`, wired via
`LibraryNav.ItemDetail` in `HomeScreen`.

## Preconditions
- App is configured with an Audiobookshelf source that has at least one library containing books.
- App launches to `HomeScreen` showing a library (the `LibraryNav.Items` state).

## Scenarios

### 3.1 — Tapping a book tile navigates to the detail screen
**Steps:**
1. Launch the app to the library grid.
2. Tap any book cover tile.

**Expected:**
- A detail screen slides in.
- The book title and author are visible.
- A purple `Read` button is visible.
- An `Add to To-Read` button is visible.

### 3.2 — Back button returns to the library
**Steps:**
1. Navigate to any item detail screen (see 3.1).
2. Tap the `← Back` button.

**Expected:**
- The library grid screen is restored.

### 3.3 — Detail screen shows a cover placeholder
**Steps:**
1. Navigate to a book tile whose cover image is absent or unavailable.

**Expected:**
- A colored gradient placeholder is rendered (ebook = purple gradient, audiobook = blue gradient).

### 3.4 — "In Progress" section item also navigates to detail
**Steps:**
1. From the library home, tap `See all` next to "In Progress".
2. Tap any book cover in the section grid.

**Expected:**
- The detail screen opens for that book (same as 3.1).

### 3.5 — To-Read toggle updates optimistically
**Steps:**
1. Navigate to the detail screen for a book not already in To-Read.
2. Tap `Add to To-Read`.

**Expected:**
- The button label changes to `In To-Read` immediately (optimistic update).

### 3.6 — Offline banner is shown when device is offline
**Steps:**
1. Put the device in airplane mode.
2. Navigate to any item detail screen.

**Expected:**
- A `You are offline` banner is visible below the action buttons.

### 3.7 — Error state for unknown item
**Steps:**
1. Force a lookup for an item ID that does not exist in the local DB (simulate by removing the
   item from the source then opening its cached detail).

**Expected:**
- The `Item not found` message is shown, with a `← Back` link.

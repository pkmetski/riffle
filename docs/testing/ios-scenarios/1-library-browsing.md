# iOS Scenario: Library Browsing (Issue #909)

Covers `LibraryItemsScreen` and `LibrarySectionScreen` rendered via `HomeScreen` on iOS.

## Preconditions
- App is configured with an Audiobookshelf source that has at least one library containing books.
- App launches to `HomeScreen`.

## Scenarios

### 1.1 — Library home renders the section grid
**Steps:**
1. Launch the app with a configured ABS source.
2. Wait for `HomeScreen` to resolve the start destination.

**Expected:**
- A `TopAppBar` is visible with the library name as the title.
- At least one section tile row ("In Progress", "Recently Added", or "All Books") is visible.
- A "See all" button is visible next to any section that has items.

### 1.2 — Loading state shows spinner
**Steps:**
1. Observe the screen immediately after destination is resolved (before library items load).

**Expected:**
- A `CircularProgressIndicator` is visible while `isLoading` is `true`.

### 1.3 — "See all" navigates to section screen
**Steps:**
1. Tap "See all" next to a section (e.g. "In Progress").

**Expected:**
- A new screen appears with `TopAppBar` titled with the section name (e.g. "In Progress").
- A grid of book cover tiles is visible.

### 1.4 — Back from section screen returns to library
**Steps:**
1. Navigate to a section screen (see 1.3).
2. Tap the back/arrow button in the `TopAppBar`.

**Expected:**
- The user is returned to the `LibraryItemsScreen` with the library name in the `TopAppBar`.

### 1.5 — Book covers load with auth header
**Steps:**
1. Observe any book cover tile with a non-null `coverUrl`.

**Expected:**
- The cover image loads (no broken image placeholder).
- Coil requests include an `Authorization` header derived from the active source's token.

### 1.6 — Missing cover shows placeholder
**Steps:**
1. Observe a book tile whose library item has no `coverUrl`.

**Expected:**
- A colored gradient placeholder is rendered (ebook = purple gradient, audiobook = blue gradient).

# iOS Scenario 7 — Bookshelf

Tests for the cross-source Bookshelf view introduced in the Bookshelf feature.

## Scenario 7.1 — Bookshelf entry appears in drawer

**Given** the app is open and the library drawer is visible.
**Then** a "Bookshelf" row appears at the top of the drawer, above the source-switcher header and the divider.

## Scenario 7.2 — Tapping Bookshelf navigates to Bookshelf screen

**Given** the drawer is open.
**When** the user taps the "Bookshelf" row.
**Then** the drawer closes and the Bookshelf screen is shown with the "In Progress", "To Read", and "Annotations" tabs.

## Scenario 7.3 — In Progress tab shows all in-progress books

**Given** the user has books in progress across multiple sources.
**When** the user opens Bookshelf and views the "In Progress" tab.
**Then** in-progress books from all sources are listed (with source-specific order), followed by a "Continue Series" section if applicable.

## Scenario 7.4 — To Read tab shows all to-read books

**Given** the user has books in to-read playlists across sources.
**When** the user selects the "To Read" tab.
**Then** all to-read items appear as a flat list.

## Scenario 7.5 — Annotations tab shows annotated books

**Given** the user has annotated books across sources.
**When** the user selects the "Annotations" tab.
**Then** all annotated books appear with title, author, and highlight count.

## Scenario 7.6 — Tapping a book in Bookshelf opens item detail

**Given** the Bookshelf screen is showing the In Progress tab.
**When** the user taps a book.
**Then** the LibraryItemDetailScreen for that book is shown.

## Scenario 7.7 — Hamburger icon opens drawer from Bookshelf screen

**Given** the user is on the Bookshelf screen (drawer closed).
**When** the user taps the hamburger icon (☰) in the top bar.
**Then** the drawer opens with the Bookshelf entry highlighted as active.

## Scenario 7.8 — Bookshelf entry highlighted when active

**Given** the user has navigated to Bookshelf.
**When** the drawer is opened from Bookshelf.
**Then** the "Bookshelf" row in the drawer has a distinct background indicating it is the active destination.

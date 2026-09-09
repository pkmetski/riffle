# iOS Scenario 7 — Riffle

Tests for the cross-source Riffle view introduced in the Riffle feature.

## Scenario 7.1 — Riffle entry appears in drawer

**Given** the app is open and the library drawer is visible.
**Then** a "Riffle" row appears at the top of the drawer, above the source-switcher header and the divider.

## Scenario 7.2 — Tapping Riffle navigates to Riffle screen

**Given** the drawer is open.
**When** the user taps the "Riffle" row.
**Then** the drawer closes and the Riffle screen is shown with the "In Progress", "To Read", and "Annotations" tabs.

## Scenario 7.3 — In Progress tab shows all in-progress books

**Given** the user has books in progress across multiple sources.
**When** the user opens Riffle and views the "In Progress" tab.
**Then** in-progress books from all sources are listed (with source-specific order), followed by a "Continue Series" section if applicable.

## Scenario 7.4 — To Read tab shows all to-read books

**Given** the user has books in to-read playlists across sources.
**When** the user selects the "To Read" tab.
**Then** all to-read items appear as a flat list.

## Scenario 7.5 — Annotations tab shows annotated books

**Given** the user has annotated books across sources.
**When** the user selects the "Annotations" tab.
**Then** all annotated books appear with title, author, and highlight count.

## Scenario 7.6 — Tapping a book in Riffle opens item detail

**Given** the Riffle screen is showing the In Progress tab.
**When** the user taps a book.
**Then** the LibraryItemDetailScreen for that book is shown.

## Scenario 7.7 — Hamburger icon opens drawer from Riffle screen

**Given** the user is on the Riffle screen (drawer closed).
**When** the user taps the hamburger icon (☰) in the top bar.
**Then** the drawer opens with the Riffle entry highlighted as active.

## Scenario 7.8 — Riffle entry highlighted when active

**Given** the user has navigated to Riffle.
**When** the drawer is opened from Riffle.
**Then** the "Riffle" row in the drawer has a distinct background indicating it is the active destination.

## Scenario 7.9 — Riffle entry hidden with fewer than 2 sources

**Given** exactly one source is configured.
**When** the user opens the drawer.
**Then** no "Riffle" row appears in the drawer.

**Given** two or more sources are configured.
**When** the user opens the drawer.
**Then** the "Riffle" row appears at the top of the drawer above the source-switcher header.

## Scenario 7.10 — Switching from Riffle back to the previously-active source navigates away on first tap

**Given** source A is the active source and the user has navigated to the Riffle screen.
**When** the user opens the drawer and taps source A (the same source that was active before entering Riffle).
**Then** the drawer closes and the user is taken to source A's library on the **first** tap, not the second.

*Regression: entering Riffle must deactivate the underlying source so that re-selecting the same source is always a state change that triggers navigation away from Riffle.*

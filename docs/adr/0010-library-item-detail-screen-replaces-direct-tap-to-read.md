# ADR 0010 — Library Item Detail Screen replaces direct tap-to-read

## Status
Accepted

## Context
Previously, tapping a Library Item card in the library list launched the reader directly. This meant metadata beyond title and author (description, series, genres, published year, publisher) was never surfaced in the app. The ABS API returns all of this data but Riffle discarded it silently.

## Decision
Tapping a Library Item card now navigates to the Library Item Detail Screen. The reader is launched from there via an explicit Read button. The direct tap-to-read shortcut is removed entirely.

Unsupported Library Items (no ebook file) are now tappable; their detail screen shows available metadata and explains why the item cannot be read, rather than offering a Read button.

## Consequences
- Users who previously tapped to read immediately now need one extra tap. This is acceptable — the detail screen provides meaningful context (description, series position) before opening a book.
- Back-navigating from the reader returns to the detail screen rather than the library list. This is the natural Android back-stack result and requires no special handling.
- The `LibraryItem` domain model must be extended with `description`, `seriesName`, `publishedYear`, `genres`, and `publisher`. These require a database migration and updated API DTO mapping.

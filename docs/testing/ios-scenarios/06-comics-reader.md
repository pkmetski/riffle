# iOS Scenario 06 — Comics (CBZ) Reader

Corresponds to Android harness tests: `CbzReaderTest`.

## Prerequisites

- A Komga source is configured with at least one CBZ book in the library.
- The book has at least 3 pages.

## Scenario 06-A: Open CBZ book from library

1. Launch the app — library browser is shown.
2. Tap a CBZ book item.
3. **Expected**: The CBZ reader screen opens. The first page image is visible within 10 s.
4. **Expected**: After opening, the "← Back" button is visible.

## Scenario 06-B: Page turn

1. Open a CBZ book (scenario 06-A).
2. Swipe left to go to the next page.
3. **Expected**: The next page image is shown.
4. **Expected**: The page counter (if visible) reflects the new page.

## Scenario 06-C: Back button returns to library

1. Open a CBZ book (scenario 06-A).
2. Tap "← Back".
3. **Expected**: The library browser is shown. No crash.

## Scenario 06-D: Reading position persists across sessions

1. Open a CBZ book and advance to page 3 or later.
2. Tap "← Back" to return to the library.
3. Re-open the same book.
4. **Expected**: The reader resumes at the last-viewed page (not page 1).

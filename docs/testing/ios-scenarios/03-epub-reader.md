# iOS Scenario 03 — EPUB Reader

Corresponds to Android harness tests: `EpubHarnessTest`, `TocIntegrationTest`,
`OrientationChangeTest`, `ReaderSettingsSectionsTest`.

## Prerequisites

- An ABS source is configured and the library contains at least one EPUB book.
- The EPUB book's download status: both cached (already downloaded) and non-cached books
  should be tested.

## Scenario 03-A: Open EPUB from library

1. Launch the app — library browser is shown.
2. Tap an EPUB book item.
3. **Expected**: The EPUB reader screen opens. The book content is visible within 5 s.
4. **Expected**: After opening, the "← Back" button is visible.

## Scenario 03-B: Page navigation

1. Open an EPUB book (scenario 03-A).
2. Swipe left to go to the next page.
3. **Expected**: Content advances to the next page.
4. Swipe right to go back.
5. **Expected**: Previous page content is shown.

## Scenario 03-C: Reading position persistence

1. Open an EPUB book and advance several pages (at least 3).
2. Tap "← Back" to return to the library.
3. Tap the same book again.
4. **Expected**: The book reopens at the same page (position restored from NSUserDefaults).

## Scenario 03-D: TOC navigation

1. Open an EPUB book that has multiple chapters.
2. (Programmatic test) Navigate via `goToLocator` with a locator JSON pointing to chapter 2.
3. **Expected**: The navigator displays chapter 2 content.

## Scenario 03-E: Back navigation

1. Open an EPUB book.
2. Tap "← Back".
3. **Expected**: Returns to the library browser, position is saved.
4. **Expected**: No crash or memory leak (navigator `release()` called).

## Scenario 03-F: Non-readable item does not open reader

1. In the library, tap an item whose `ebookFormat` is `Unsupported`.
2. **Expected**: No reader screen is opened (`isReadable == false` gate).

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

## Scenario 03-H: Progress is restored when opening from the Riffle home screen

1. Open an EPUB book from a non-primary source (e.g. Chitanka/Gutenberg when ABS is active) and advance several pages.
2. Tap "← Back" to close the reader.
3. Navigate to the Riffle home (in-progress) screen.
4. Tap the same book.
5. **Expected**: The reader reopens at the page where reading stopped — **not** at the beginning.

## Scenario 03-G: Open downloaded EPUB from source browse (sourceId regression)

1. Ensure a downloaded EPUB from a non-primary source (e.g. Chitanka/Gutenberg when ABS is active) exists.
2. Navigate to that source's browse screen via the drawer.
3. Tap the downloaded book → detail screen.
4. Tap **Read**.
5. **Expected**: The EPUB reader opens successfully — "Book not found" must NOT appear.

## Scenario 03-I: Progress bar is per-source when the same book exists on two sources

1. Configure two sources (e.g. ABS and O'Reilly) that both carry the same title (e.g. "Fundamentals of Software Architecture").
2. Open the ABS copy, advance to ~25 % of the book, and close.
3. Open the O'Reilly copy, advance to ~75 % of the book, and close.
4. Navigate to the Riffle home screen ("In Progress" section).
5. **Expected**: Both copies appear with **different** progress bars — ~25 % for the ABS copy and ~75 % for the O'Reilly copy. Neither copy must display the other's progress.

## Scenario 03-J: Lazy (O'Reilly) book opens at saved position when position was pre-migration

1. Configure ABS and O'Reilly sources. Ensure the O'Reilly book's saved position row (if any) lives only under the ABS source id (simulates pre-PR-999 data — e.g. clear the O'Reilly row from `reading_positions` for the book while leaving the ABS row intact with a valid locator).
2. Open the O'Reilly book from the Riffle home or the O'Reilly browse screen.
3. **Expected**: The reader opens at the position recorded under the ABS source id (the backward-compat fallback fires), NOT at the cover page.
4. Advance a few pages and close the reader.
5. Re-open the same O'Reilly book.
6. **Expected**: The reader opens at the position saved in step 4 (now under the O'Reilly source id — the fallback is no longer needed).

## Scenario 03-K: Lazy (O'Reilly) book opens at saved position when locator has OEBPS-prefixed href

1. Configure an O'Reilly source. Open an O'Reilly book that was previously read as a downloaded EPUB (its position stored with an `"OEBPS/"` href prefix, e.g. `{"href":"OEBPS/ch09.html",...}`). This can be simulated by manually inserting a row in `reading_positions` with `href = "OEBPS/ch09.html"` for the book.
2. Open the same O'Reilly book lazily (i.e. without having downloaded the EPUB first).
3. **Expected**: The reader opens at the chapter corresponding to `ch09.html`, NOT at the cover page. The `"OEBPS/"` prefix in the stored locator must be silently normalized to match the lazy publication's bare reading-order paths.
4. The progress bar must show non-zero progress matching the stored position.

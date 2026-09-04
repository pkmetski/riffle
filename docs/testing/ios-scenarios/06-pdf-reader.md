# 06 — iOS PDF Reader

**Feature**: PDF reader on iOS using PDFKit  
**Android reference tests**: `PdfHarnessTest`, `ReaderSettingsSheetCapabilitiesTest` (PDF capabilities)

---

## Scenario 06-A: Bridge lifecycle

- `PdfKitNavigatorBridgeFactoryImpl.create()` returns distinct instances.
- `viewController()` returns a non-nil `UIViewController`.
- `pageCount()` returns 0 before `openPdf` is called.
- `currentPage()` returns 0 before `openPdf` is called.
- `disposePdf()` is idempotent (calling twice must not crash).

## Scenario 06-B: Opening a PDF

- After `openPdf(filePath:initialPage:)`, `pageCount()` returns the document's page count.
- If `initialPage > 0`, the viewer navigates to that page on open.
- If `initialPage == 0`, the viewer starts at the first page.

## Scenario 06-C: Page navigation

- `goToPage(pageIndex:)` navigates to the target 0-based page.
- `currentPage()` reflects the current 0-based page index after navigation.
- `goToPage` with an out-of-range index is a no-op (no crash).

## Scenario 06-D: Page-change callback

- The page-change callback registered via `setPageChangeCallback` is invoked whenever the user turns pages.
- Clearing the callback (passing nil) stops further invocations.

## Scenario 06-E: Position persistence

- Opening a PDF book, reading to page N, then closing and re-opening the book resumes at page N.
- Position is stored per `(sourceId, itemId)` pair — different books store independently.

## Scenario 06-F: Error handling

- When the PDF file cannot be found, the screen shows an error message.
- When the network download fails, the screen shows an error message (no crash).

## Scenario 06-G: Back navigation

- Tapping the "← Back" button returns to the library screen.
- Returning from the reader persists the last-read page before disposing.

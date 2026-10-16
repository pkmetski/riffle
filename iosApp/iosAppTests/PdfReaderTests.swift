import XCTest

// Covers PDF reader scenarios (scenario 06). Uses AbsHarnessTestCase so the stub ABS server
// provides a "Test PDF" item; no XCTSkip.
final class PdfReaderTests: AbsHarnessTestCase {

    private var pdfTile: XCUIElement {
        app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'pdf'")
        ).firstMatch
    }

    // MARK: - Scenario 06-B: Opening a PDF

    /// 06-B.1 — Tapping a PDF item and choosing Read opens the PDF reader screen.
    func testPdfReaderOpensFromLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        // Gate on the home sections, not just the (absent) spinner: the 10 s tile wait below
        // starts counting from app launch otherwise and trips under CI clone contention.
        XCTAssertTrue(waitForLibraryHome(in: app), "Library home must load")
        XCTAssertTrue(waitForLibraryTile(pdfTile, in: app, timeout: 10), "PDF tile must be visible in the library")

        let backButton = openReader(from: pdfTile, in: app)
        XCTAssertTrue(backButton.exists, "PDF reader should show a Back button after opening")
        XCTAssertFalse(app.staticTexts["Error"].exists, "PDF reader must not show a load error")
    }

    // MARK: - Scenario 06-G: Back navigation

    /// 06-G.1 — Tapping back from the PDF reader returns to the library.
    func testPdfReaderBackNavigationReturnsToLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        // Gate on the home sections, not just the (absent) spinner: the 10 s tile wait below
        // starts counting from app launch otherwise and trips under CI clone contention.
        XCTAssertTrue(waitForLibraryHome(in: app), "Library home must load")
        XCTAssertTrue(waitForLibraryTile(pdfTile, in: app, timeout: 10))

        let backButton = openReader(from: pdfTile, in: app)
        XCTAssertTrue(backButton.exists, "PDF reader must open")
        tapBackToLibrary(backButton, in: app)
    }

    // MARK: - Scenario 06-H: State tags and bookmark ribbon

    /// 06-H.1 — The PDF reader container state tag is present once the reader opens.
    func testPdfReaderContainerTagPresent() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(waitForLibraryHome(in: app), "Library home must load")
        XCTAssertTrue(waitForLibraryTile(pdfTile, in: app, timeout: 10), "PDF tile must be visible")

        _ = openReader(from: pdfTile, in: app)
        let container = app.otherElements["pdf_reader_container"]
        XCTAssertTrue(
            container.waitForExistence(timeout: 10),
            "pdf_reader_container tag must be present once the PDF reader opens"
        )
    }

    /// 06-H.2 — The bookmark ribbon button is present and tappable once the reader is ready.
    func testPdfReaderBookmarkRibbonPresent() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(waitForLibraryHome(in: app), "Library home must load")
        XCTAssertTrue(waitForLibraryTile(pdfTile, in: app, timeout: 10), "PDF tile must be visible")

        _ = openReader(from: pdfTile, in: app)
        // Wait for reader_ready tag which appears once the VM transitions to Ready state.
        let ready = app.otherElements["reader_ready"]
        XCTAssertTrue(ready.waitForExistence(timeout: 15), "PDF reader must reach Ready state")

        let bookmark = app.buttons["pdf_reader_bookmark"]
        XCTAssertTrue(bookmark.waitForExistence(timeout: 5), "Bookmark ribbon button must be present")
        bookmark.tap()
        // After toggle the button still exists (not a one-shot action).
        XCTAssertTrue(bookmark.exists, "Bookmark ribbon must remain after toggle")
    }
}

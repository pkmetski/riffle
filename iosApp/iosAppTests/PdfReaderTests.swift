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
        XCTAssertTrue(pdfTile.waitForExistence(timeout: 10), "PDF tile must be visible in the library")

        let backButton = openReader(from: pdfTile, in: app)
        XCTAssertTrue(backButton.exists, "PDF reader should show a Back button after opening")
        XCTAssertFalse(app.staticTexts["Error"].exists, "PDF reader must not show a load error")
    }

    // MARK: - Scenario 06-G: Back navigation

    /// 06-G.1 — Tapping back from the PDF reader returns to the library.
    func testPdfReaderBackNavigationReturnsToLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(pdfTile.waitForExistence(timeout: 10))

        let backButton = openReader(from: pdfTile, in: app)
        XCTAssertTrue(backButton.exists, "PDF reader must open")
        tapBackToLibrary(backButton, in: app)
    }
}

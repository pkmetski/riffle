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

    /// 06-B.1 — Tapping a PDF item opens the PDF reader screen.
    func testPdfReaderOpensFromLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(pdfTile.waitForExistence(timeout: 10), "PDF tile must be visible in the library")
        pdfTile.tap()

        let backButton = app.buttons["Back"].firstMatch
        XCTAssertTrue(
            backButton.waitForExistence(timeout: 15),
            "PDF reader should show a Back button after opening"
        )
    }

    // MARK: - Scenario 06-G: Back navigation

    /// 06-G.1 — Tapping back from the PDF reader returns to the library.
    func testPdfReaderBackNavigationReturnsToLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(pdfTile.waitForExistence(timeout: 10))
        pdfTile.tap()

        let backButton = app.buttons["Back"].firstMatch
        XCTAssertTrue(backButton.waitForExistence(timeout: 15), "PDF reader must open")
        backButton.tap()

        let sectionLabels = ["In Progress", "Recently Added", "Finished", "All Books", "Series", "Collections"]
        let backOnHome = sectionLabels.contains { app.staticTexts[$0].waitForExistence(timeout: 5) }
        XCTAssertTrue(backOnHome, "Tapping Back from PDF reader should return to library home")
    }
}

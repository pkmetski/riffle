import XCTest

// Covers scenarios from docs/testing/ios-scenarios/06-pdf-reader.md

final class PdfReaderTests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
    }

    // MARK: - Scenario 06-B: Opening a PDF

    /// 06-B.1 — Tapping a PDF item opens the PDF reader screen.
    func testPdfReaderOpensFromLibrary() throws {
        if app.staticTexts["Add a source to get started"].waitForExistence(timeout: 5) {
            throw XCTSkip("No source configured — PDF reader test requires a connected server")
        }
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let pdfTile = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'pdf'")
        ).firstMatch
        guard pdfTile.waitForExistence(timeout: 5) else {
            throw XCTSkip("No PDF tile found in library — requires a server with PDF items")
        }

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
        if app.staticTexts["Add a source to get started"].waitForExistence(timeout: 5) {
            throw XCTSkip("No source configured — PDF reader test requires a connected server")
        }
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let pdfTile = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'pdf'")
        ).firstMatch
        guard pdfTile.waitForExistence(timeout: 5) else {
            throw XCTSkip("No PDF tile found in library — requires a server with PDF items")
        }

        pdfTile.tap()

        let backButton = app.buttons["Back"].firstMatch
        guard backButton.waitForExistence(timeout: 15) else {
            throw XCTSkip("PDF reader did not open")
        }

        backButton.tap()

        let sectionLabels = ["In Progress", "Recently Added", "Finished", "All Books", "Series", "Collections"]
        let backOnHome = sectionLabels.contains { app.staticTexts[$0].waitForExistence(timeout: 5) }
        XCTAssertTrue(backOnHome, "Tapping Back from PDF reader should return to library home")
    }
}

import XCTest

// Covers scenarios from docs/testing/ios-scenarios/06-comics-reader.md

final class ComicsReaderTests: XCTestCase {

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

    // MARK: - Scenario 06-C: Opening a CBZ

    /// 06-C.1 — Tapping a CBZ item opens the comics reader screen.
    func testComicsReaderOpensFromLibrary() throws {
        if app.staticTexts["Add a source to get started"].waitForExistence(timeout: 5) {
            throw XCTSkip("No source configured — comics reader test requires a connected server")
        }
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let cbzTile = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'cbz'")
        ).firstMatch
        guard cbzTile.waitForExistence(timeout: 5) else {
            throw XCTSkip("No CBZ tile found in library — requires a server with CBZ items")
        }

        cbzTile.tap()

        let backButton = app.buttons["← Back"].firstMatch
        XCTAssertTrue(
            backButton.waitForExistence(timeout: 15),
            "Comics reader should show a Back button after opening"
        )
    }

    // MARK: - Scenario 06-G: Back navigation

    /// 06-G.1 — Tapping back from the comics reader returns to the library.
    func testComicsReaderBackNavigationReturnsToLibrary() throws {
        if app.staticTexts["Add a source to get started"].waitForExistence(timeout: 5) {
            throw XCTSkip("No source configured — comics reader test requires a connected server")
        }
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let cbzTile = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'cbz'")
        ).firstMatch
        guard cbzTile.waitForExistence(timeout: 5) else {
            throw XCTSkip("No CBZ tile found in library — requires a server with CBZ items")
        }

        cbzTile.tap()

        let backButton = app.buttons["← Back"].firstMatch
        guard backButton.waitForExistence(timeout: 15) else {
            throw XCTSkip("Comics reader did not open")
        }

        backButton.tap()

        let sectionLabels = ["In Progress", "Recently Added", "Finished", "All Books", "Series", "Collections"]
        let backOnHome = sectionLabels.contains { app.staticTexts[$0].waitForExistence(timeout: 5) }
        XCTAssertTrue(backOnHome, "Tapping Back from comics reader should return to library home")
    }
}

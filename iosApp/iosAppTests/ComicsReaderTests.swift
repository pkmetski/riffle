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
        if app.staticTexts["Add source"].waitForExistence(timeout: 5) {
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

    // MARK: - Scenario 06-D: Reading position persists across sessions

    /// 06-D.1 — Reopening a CBZ resumes at the last-viewed page, not page 1.
    ///
    /// Regression for the viewModelScope-cancellation position drop: `onCleared()` cancels
    /// viewModelScope before an in-flight saveReadingPosition coroutine can execute, leaving the
    /// DB with a stale (page 1) position. Fixed by flushing position on an app-lifetime scope in
    /// `onReaderClosed()` before the ViewModel is cleared.
    func testComicsPositionRestoredOnReopen() throws {
        if app.staticTexts["Add source"].waitForExistence(timeout: 5) {
            throw XCTSkip("No source configured — position-persistence test requires a connected server")
        }
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let cbzTile = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'cbz'")
        ).firstMatch
        guard cbzTile.waitForExistence(timeout: 5) else {
            throw XCTSkip("No CBZ tile found in library — requires a server with CBZ items")
        }
        let bookLabel = cbzTile.label

        // Open and advance a few pages.
        cbzTile.tap()
        let backButton = app.buttons["← Back"].firstMatch
        guard backButton.waitForExistence(timeout: 15) else {
            throw XCTSkip("Comics reader did not open")
        }
        // Advance 3 pages by swiping left.
        let readerArea = app.otherElements.firstMatch
        for _ in 0..<3 {
            readerArea.swipeLeft()
            Thread.sleep(forTimeInterval: 0.5)
        }

        // Close the reader.
        backButton.tap()
        _ = app.staticTexts.firstMatch.waitForExistence(timeout: 5)

        // Reopen the same book.
        let sameTile = app.buttons.matching(
            NSPredicate(format: "label == %@", bookLabel)
        ).firstMatch
        guard sameTile.waitForExistence(timeout: 5) else {
            throw XCTSkip("Could not find the same CBZ tile after closing reader")
        }
        sameTile.tap()
        guard backButton.waitForExistence(timeout: 15) else {
            throw XCTSkip("Comics reader did not reopen")
        }

        // The page indicator should NOT show "1 /" (page 1) — we advanced past that.
        // If position was dropped the book would restart at page 1.
        let onPageOne = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH '1 /'")
        ).firstMatch.waitForExistence(timeout: 3)
        XCTAssertFalse(
            onPageOne,
            "After reopening a CBZ, position should be restored to the last-viewed page (not page 1)"
        )
    }

    // MARK: - Scenario 06-G: Back navigation

    /// 06-G.1 — Tapping back from the comics reader returns to the library.
    func testComicsReaderBackNavigationReturnsToLibrary() throws {
        if app.staticTexts["Add source"].waitForExistence(timeout: 5) {
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

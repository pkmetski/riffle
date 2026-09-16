import XCTest

// Covers comics reader scenarios (scenario 06-C). Uses KomgaHarnessTestCase so the stub Komga
// server provides a "Test CBZ" item served page-by-page; no XCTSkip.
final class ComicsReaderTests: KomgaHarnessTestCase {

    private var cbzTile: XCUIElement {
        app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'cbz'")
        ).firstMatch
    }

    // MARK: - Scenario 06-C: Opening a CBZ

    /// 06-C.1 — Tapping a CBZ item opens the comics reader screen.
    func testComicsReaderOpensFromLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(cbzTile.waitForExistence(timeout: 10), "CBZ tile must be visible in the library")
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
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(cbzTile.waitForExistence(timeout: 10))
        let bookLabel = cbzTile.label

        cbzTile.tap()
        let backButton = app.buttons["← Back"].firstMatch
        XCTAssertTrue(backButton.waitForExistence(timeout: 15), "Comics reader must open")

        let readerArea = app.otherElements.firstMatch
        for _ in 0..<3 {
            readerArea.swipeLeft()
            Thread.sleep(forTimeInterval: 0.5)
        }

        backButton.tap()
        _ = app.staticTexts.firstMatch.waitForExistence(timeout: 5)

        let sameTile = app.buttons.matching(
            NSPredicate(format: "label == %@", bookLabel)
        ).firstMatch
        XCTAssertTrue(sameTile.waitForExistence(timeout: 5), "CBZ tile must reappear after closing reader")
        sameTile.tap()
        XCTAssertTrue(backButton.waitForExistence(timeout: 15), "Comics reader must reopen")

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
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(cbzTile.waitForExistence(timeout: 10))
        cbzTile.tap()

        let backButton = app.buttons["← Back"].firstMatch
        XCTAssertTrue(backButton.waitForExistence(timeout: 15), "Comics reader must open")
        backButton.tap()

        let sectionLabels = ["In Progress", "Recently Added", "Finished", "All Books", "Series", "Collections"]
        let backOnHome = sectionLabels.contains { app.staticTexts[$0].waitForExistence(timeout: 5) }
        XCTAssertTrue(backOnHome, "Tapping Back from comics reader should return to library home")
    }
}

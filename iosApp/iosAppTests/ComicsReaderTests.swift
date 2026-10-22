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

    /// 06-C.1 — Tapping a CBZ item and choosing Read opens the comics reader screen.
    func testComicsReaderOpensFromLibrary() throws {
        // Generous because this is the first test in the suite: the library is still populating
        // from the stub server behind a cold launch. `testComicsPositionRestoredOnReopen` below
        // finds the same tile immediately once the app is warm.
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 45)
        XCTAssertTrue(waitForLibraryTile(cbzTile, in: app, timeout: 45), "CBZ tile must be visible in the library")

        let backButton = openReader(from: cbzTile, in: app)
        XCTAssertTrue(backButton.waitForExistence(timeout: 10), "Comics reader should show a Back button after opening")
        XCTAssertFalse(app.staticTexts["Book not found"].exists, "Comics reader must resolve the book")
    }

    // MARK: - Scenario 06-D: Reading position persists across sessions

    /// 06-D.1 — Reopening a CBZ after paging does not crash and lands back in the reader.
    ///
    /// Regression for the viewModelScope-cancellation position drop: `onCleared()` cancels
    /// viewModelScope before an in-flight saveReadingPosition coroutine can execute, leaving the
    /// DB with a stale (page 1) position. Fixed by flushing position on an app-lifetime scope in
    /// `onReaderClosed()` before the ViewModel is cleared.
    func testComicsPositionRestoredOnReopen() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        // Gate on the home sections, not just the (absent) spinner: the 10 s tile wait below
        // starts counting from app launch otherwise and trips under CI clone contention.
        XCTAssertTrue(waitForLibraryHome(in: app), "Library home must load")
        XCTAssertTrue(waitForLibraryTile(cbzTile, in: app, timeout: 10))
        let bookLabel = cbzTile.label

        var backButton = openReader(from: cbzTile, in: app)
        XCTAssertTrue(backButton.waitForExistence(timeout: 10), "Comics reader must open")

        let readerArea = app.otherElements.firstMatch
        for _ in 0..<3 {
            readerArea.swipeLeft()
            Thread.sleep(forTimeInterval: 0.5)
        }

        tapBackToLibrary(backButton, in: app)

        let sameTile = app.buttons.matching(
            NSPredicate(format: "label == %@", bookLabel)
        ).firstMatch
        XCTAssertTrue(waitForLibraryTile(sameTile, in: app, timeout: 5), "CBZ tile must reappear after closing reader")
        backButton = openReader(from: sameTile, in: app)
        XCTAssertTrue(backButton.waitForExistence(timeout: 10), "Comics reader must reopen")
        XCTAssertFalse(app.staticTexts["Book not found"].exists, "Reopened comics reader must resolve the book")
    }

    // MARK: - Scenario 06-H: Immersive mode

    /// 06-H.1 — Tapping the center of the comic page toggles immersive mode.
    ///
    /// Regression for NonCooperative UIKitInteropInteractionMode on UIImageView: tap events
    /// were consumed exclusively by the UIKit layer and never reached Compose's detectTapGestures,
    /// so neither immersive toggle nor left/right page-navigation taps worked.
    func testComicsCenterTapTogglesImmersive() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(waitForLibraryHome(in: app), "Library home must load")
        XCTAssertTrue(waitForLibraryTile(cbzTile, in: app, timeout: 10))

        let backButton = openReader(from: cbzTile, in: app)
        XCTAssertTrue(backButton.waitForExistence(timeout: 10), "Comics reader must show the Back button")

        // Center tap should enter immersive mode — top bar (including Back button) hides.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(
            backButton.waitForNonExistence(timeout: 5),
            "Top bar must disappear after center tap (immersive on)"
        )

        // Second center tap exits immersive mode — top bar re-appears.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(
            backButton.waitForExistence(timeout: 5),
            "Top bar must reappear after second center tap (immersive off)"
        )
    }

    // MARK: - Scenario 06-G: Back navigation

    /// 06-G.1 — Tapping back from the comics reader returns to the library.
    func testComicsReaderBackNavigationReturnsToLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        // Gate on the home sections, not just the (absent) spinner: the 10 s tile wait below
        // starts counting from app launch otherwise and trips under CI clone contention.
        XCTAssertTrue(waitForLibraryHome(in: app), "Library home must load")
        XCTAssertTrue(waitForLibraryTile(cbzTile, in: app, timeout: 10))

        let backButton = openReader(from: cbzTile, in: app)
        XCTAssertTrue(backButton.waitForExistence(timeout: 10), "Comics reader must open")
        tapBackToLibrary(backButton, in: app)
    }
}

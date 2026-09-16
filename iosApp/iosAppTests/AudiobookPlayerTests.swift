import XCTest

// Covers audiobook player scenarios (issue #909 / scenario 04). Uses AbsHarnessTestCase so
// the stub ABS server provides a listenable "Test Audiobook" item; no XCTSkip.
final class AudiobookPlayerTests: AbsHarnessTestCase {

    private var audiobookTile: XCUIElement {
        app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'audiobook'")
        ).firstMatch
    }

    // MARK: - Scenario 04-A: Player opens from library

    /// 04-A.1 — Tapping a listenable item opens the audiobook player screen.
    func testAudiobookPlayerOpensFromLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(audiobookTile.waitForExistence(timeout: 10),
                      "Audiobook tile must be visible in the library")
        audiobookTile.tap()
        XCTAssertTrue(
            app.staticTexts["← Back"].waitForExistence(timeout: 15),
            "Audiobook player screen should show '← Back'"
        )
    }

    // MARK: - Scenario 04-C: Player controls visible

    /// 04-C.1 — Player screen shows play/pause control.
    func testAudiobookPlayerControlsVisible() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(audiobookTile.waitForExistence(timeout: 10))
        audiobookTile.tap()
        XCTAssertTrue(app.staticTexts["← Back"].waitForExistence(timeout: 15),
                      "Player screen must open")

        let playPause = app.buttons.matching(
            NSPredicate(format: "label == '▶' OR label == '⏸'")
        ).firstMatch
        XCTAssertTrue(
            playPause.waitForExistence(timeout: 10),
            "Play/pause button should be visible on the player screen"
        )
    }

    // MARK: - Scenario 04-G: Back navigation

    /// 04-G.1 — Tapping '← Back' from the player returns to the library.
    func testAudiobookPlayerBackNavigationReturnsToLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(audiobookTile.waitForExistence(timeout: 10))
        audiobookTile.tap()

        let backArrow = app.staticTexts["← Back"].firstMatch
        XCTAssertTrue(backArrow.waitForExistence(timeout: 15), "Player screen must open")
        backArrow.tap()

        let sectionLabels = ["In Progress", "Recently Added", "Finished", "All Books", "Series", "Collections"]
        let backOnHome = sectionLabels.contains { app.staticTexts[$0].waitForExistence(timeout: 5) }
        XCTAssertTrue(backOnHome, "Tapping '← Back' from player should return to library home")
    }
}

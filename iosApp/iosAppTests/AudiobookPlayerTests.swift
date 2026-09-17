import XCTest

// Covers audiobook player scenarios (issue #909 / scenario 04). Uses AbsHarnessTestCase so
// the stub ABS server provides a listenable "Test Audiobook" item; no XCTSkip.
final class AudiobookPlayerTests: AbsHarnessTestCase {

    private var audiobookTile: XCUIElement {
        app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'audiobook'")
        ).firstMatch
    }

    private var playPause: XCUIElement {
        app.buttons.matching(
            NSPredicate(format: "label == '▶' OR label == '⏸'")
        ).firstMatch
    }

    // MARK: - Scenario 04-A: Player opens from library

    /// 04-A.1 — Opening a listenable item lands on the audiobook player screen.
    func testAudiobookPlayerOpensFromLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(audiobookTile.waitForExistence(timeout: 10),
                      "Audiobook tile must be visible in the library")

        let backButton = openReader(from: audiobookTile, in: app)
        XCTAssertTrue(backButton.exists, "Audiobook player screen should show '← Back'")
        XCTAssertTrue(playPause.waitForExistence(timeout: 15), "Player screen must show its play/pause control")
    }

    // MARK: - Scenario 04-C: Player controls visible

    /// 04-C.1 — Player screen shows play/pause control.
    func testAudiobookPlayerControlsVisible() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(audiobookTile.waitForExistence(timeout: 10))

        let backButton = openReader(from: audiobookTile, in: app)
        XCTAssertTrue(backButton.exists, "Player screen must open")
        XCTAssertTrue(
            playPause.waitForExistence(timeout: 15),
            "Play/pause button should be visible on the player screen"
        )
    }

    // MARK: - Scenario 04-G: Back navigation

    /// 04-G.1 — Tapping '← Back' from the player returns to the library.
    func testAudiobookPlayerBackNavigationReturnsToLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(audiobookTile.waitForExistence(timeout: 10))

        let backButton = openReader(from: audiobookTile, in: app)
        XCTAssertTrue(backButton.exists, "Player screen must open")
        backButton.tap()

        XCTAssertTrue(waitForLibraryHome(in: app), "Tapping '← Back' from player should return to library home")
    }
}

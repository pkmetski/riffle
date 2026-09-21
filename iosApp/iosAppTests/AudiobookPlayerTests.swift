import XCTest

// Covers audiobook player scenarios (issue #909 / scenario 04). Uses AbsHarnessTestCase so
// the stub ABS server provides a listenable "Test Audiobook" item; no XCTSkip.
final class AudiobookPlayerTests: AbsHarnessTestCase {

    private var audiobookTile: XCUIElement {
        app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'audiobook'")
        ).firstMatch
    }

    // The transport is the shared `:feature:player-ui` chrome now, so the play/pause control is a
    // Material icon button whose accessibility label is "Play"/"Pause" rather than a "▶"/"⏸" glyph.
    private var playPause: XCUIElement {
        app.buttons.matching(
            NSPredicate(format: "label == 'Play' OR label == 'Pause'")
        ).firstMatch
    }

    private var chaptersPill: XCUIElement { app.buttons["Chapters"].firstMatch }

    // MARK: - Scenario 04-A: Player opens from library

    /// 04-A.1 — Opening a listenable item lands on the audiobook player screen.
    func testAudiobookPlayerOpensFromLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(audiobookTile.waitForExistence(timeout: 10),
                      "Audiobook tile must be visible in the library")

        let backButton = openReader(from: audiobookTile, in: app)
        XCTAssertTrue(backButton.exists, "Audiobook player screen should show its back control")
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

    /// 04-C.2 — The player renders the shared chrome, not the old three-button stub: a speed pill,
    /// a sleep-timer pill and the Chapters / bookmarks entry points (#1072 §3). Before the move to
    /// `:feature:player-ui` iOS had none of these.
    func testAudiobookPlayerShowsSpeedSleepAndListControls() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(audiobookTile.waitForExistence(timeout: 10))

        let backButton = openReader(from: audiobookTile, in: app)
        XCTAssertTrue(backButton.exists, "Player screen must open")
        XCTAssertTrue(playPause.waitForExistence(timeout: 15), "Player must finish loading")

        XCTAssertTrue(chaptersPill.waitForExistence(timeout: 10), "Player must offer the Chapters list")
        XCTAssertTrue(
            app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'bookmark'")).firstMatch.exists,
            "Player must offer the bookmarks list"
        )
        XCTAssertTrue(app.buttons["Sleep"].firstMatch.exists, "Player must offer the sleep timer")
        XCTAssertTrue(
            app.buttons.matching(NSPredicate(format: "label CONTAINS '×'")).firstMatch.exists,
            "Player must offer the playback-speed control"
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

        XCTAssertTrue(waitForLibraryHome(in: app), "Tapping back from the player should return to library home")
    }
}

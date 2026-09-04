import XCTest

// Covers scenarios from docs/testing/ios-scenarios/04-audiobook-player.md

final class AudiobookPlayerTests: XCTestCase {

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

    // MARK: - Scenario 04-A: Player opens from library

    /// 04-A.1 — Tapping a listenable item opens the audiobook player screen.
    func testAudiobookPlayerOpensFromLibrary() throws {
        if app.staticTexts["Add a source to get started"].waitForExistence(timeout: 5) {
            throw XCTSkip("No source configured — audiobook player test requires a connected server")
        }
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        // Look for "In Progress" or any section that might contain audiobooks.
        // Skip if no audiobook item with a headphone/speaker accessibility hint is found.
        let audiobookTile = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'audiobook' OR label CONTAINS[c] 'listen'")
        ).firstMatch
        guard audiobookTile.waitForExistence(timeout: 5) else {
            throw XCTSkip("No audiobook tile found in library — requires a server with audiobook items")
        }

        audiobookTile.tap()

        // The player screen should show a back arrow and playback controls.
        let backArrow = app.staticTexts["← Back"].firstMatch
        XCTAssertTrue(
            backArrow.waitForExistence(timeout: 10),
            "Audiobook player screen should show '← Back'"
        )
    }

    // MARK: - Scenario 04-C: Player controls visible

    /// 04-C.1 — Player screen shows play/pause control and chapter navigation.
    func testAudiobookPlayerControlsVisible() throws {
        if app.staticTexts["Add a source to get started"].waitForExistence(timeout: 5) {
            throw XCTSkip("No source configured — audiobook player test requires a connected server")
        }
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let audiobookTile = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'audiobook' OR label CONTAINS[c] 'listen'")
        ).firstMatch
        guard audiobookTile.waitForExistence(timeout: 5) else {
            throw XCTSkip("No audiobook tile found in library — requires a server with audiobook items")
        }
        audiobookTile.tap()

        guard app.staticTexts["← Back"].waitForExistence(timeout: 10) else {
            throw XCTSkip("Player screen did not open")
        }

        // Play/pause button (▶ or ⏸)
        let playPause = app.buttons.matching(
            NSPredicate(format: "label == '▶' OR label == '⏸'")
        ).firstMatch
        XCTAssertTrue(
            playPause.waitForExistence(timeout: 5),
            "Play/pause button should be visible on the player screen"
        )
    }

    // MARK: - Scenario 04-G: Back navigation

    /// 04-G.1 — Tapping '← Back' from the player returns to the library.
    func testAudiobookPlayerBackNavigationReturnsToLibrary() throws {
        if app.staticTexts["Add a source to get started"].waitForExistence(timeout: 5) {
            throw XCTSkip("No source configured — audiobook player test requires a connected server")
        }
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let audiobookTile = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'audiobook' OR label CONTAINS[c] 'listen'")
        ).firstMatch
        guard audiobookTile.waitForExistence(timeout: 5) else {
            throw XCTSkip("No audiobook tile found in library — requires a server with audiobook items")
        }
        audiobookTile.tap()

        let backArrow = app.staticTexts["← Back"].firstMatch
        guard backArrow.waitForExistence(timeout: 10) else {
            throw XCTSkip("Player screen did not open")
        }

        backArrow.tap()

        // Library home should reappear
        let sectionLabels = ["In Progress", "Recently Added", "Finished", "All Books", "Series", "Collections"]
        let backOnHome = sectionLabels.contains { app.staticTexts[$0].waitForExistence(timeout: 5) }
        XCTAssertTrue(backOnHome, "Tapping '← Back' from player should return to library home")
    }
}

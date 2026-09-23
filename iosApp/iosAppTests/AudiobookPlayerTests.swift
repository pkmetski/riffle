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

    /// Match the pills on their Compose `testTag`, which reaches iOS as an
    /// `accessibilityIdentifier`, not on their visible label.
    ///
    /// Two reasons. The label is not stable — the sleep pill merges its icon's
    /// `contentDescription` ("Sleep timer") with its text ("Sleep"), so an exact `buttons["Sleep"]`
    /// match never hits it. And #1072 is about to localise `shared`, at which point every
    /// English-text selector in this suite breaks; identifiers do not.
    private func pill(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == %@", identifier))
            .firstMatch
    }

    private var chaptersPill: XCUIElement { pill("player_chapters_pill") }

    // MARK: - Scenario 04-A: Player opens from library

    /// 04-A.1 — Opening a listenable item lands on the audiobook player screen.
    func testAudiobookPlayerOpensFromLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(audiobookTile.waitForExistence(timeout: 10),
                      "Audiobook tile must be visible in the library")

        let backButton = openReader(from: audiobookTile, in: app)
        XCTAssertTrue(backButton.exists, "Audiobook player screen should show its back control")
        XCTAssertTrue(playPause.waitForExistence(timeout: 30), "Player screen must show its play/pause control")
    }

    // MARK: - Scenario 04-C: Player controls visible

    /// 04-C.1 — Player screen shows play/pause control.
    func testAudiobookPlayerControlsVisible() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(audiobookTile.waitForExistence(timeout: 10))

        let backButton = openReader(from: audiobookTile, in: app)
        XCTAssertTrue(backButton.exists, "Player screen must open")
        XCTAssertTrue(
            playPause.waitForExistence(timeout: 30),
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
        // 150 s: the CMP iOS accessibility bridge populates the player controls asynchronously
        // after the reader screen opens. On Clone 1 after 5+ min of sequential heavy tests the
        // tree can take >90 s to settle; 150 s covers the worst observed lag while remaining
        // well under the 600 s per-test execution allowance.
        XCTAssertTrue(playPause.waitForExistence(timeout: 150), "Player must finish loading")

        // The pills all appear when loading=false, but the iOS accessibility tree is populated
        // incrementally — a pill can be on-screen while its identifier hasn't landed in the tree
        // yet. Use waitForExistence rather than .exists for each pill so a brief lag doesn't
        // produce a spurious failure.
        //
        // testTag identifiers are not used here because CMP's iOS accessibility bridge does not
        // reliably expose them for sibling buttons inside a shared container: only the first
        // element in a group propagates its identifier to XCUITest. Label predicates are stable
        // for these pills because their text content is fixed in the stub environment:
        //   - chapters: "Chapters" (static label, first in its Row → identifier works)
        //   - bookmarks: "0 bookmarks" (AssistChip, second in Row → identifier silently absent)
        //   - sleep: icon contentDescription "Sleep timer" + text "Sleep" → CONTAINS 'sleep'
        //   - speed: icon contentDescription null + text "1×" → CONTAINS '×'
        // 45 s per pill: label predicates match immediately on a fast clone (< 1 s each) but the
        // CMP iOS accessibility bridge updates the tree asynchronously — on a heavily loaded CI
        // runner (e.g. Clone 1 after a 250 s testAddAbsSourceEndToEnd) the lag can reach 20+ s
        // even though the element is visually present. 30 s proved too tight when the preceding
        // playPause wait also consumed its full budget on the same loaded runner.
        XCTAssertTrue(chaptersPill.waitForExistence(timeout: 45), "Player must offer the Chapters list")
        let bookmarksPill = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'bookmark'")
        ).firstMatch
        XCTAssertTrue(bookmarksPill.waitForExistence(timeout: 45), "Player must offer the bookmarks list")
        let sleepPill = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'sleep'")
        ).firstMatch
        XCTAssertTrue(sleepPill.waitForExistence(timeout: 45), "Player must offer the sleep timer")
        // '×' is U+00D7 (multiplication sign), matching PlaybackSpeed.label output e.g. "1×".
        let speedPill = app.buttons.matching(
            NSPredicate(format: "label CONTAINS '×'")
        ).firstMatch
        XCTAssertTrue(speedPill.waitForExistence(timeout: 45), "Player must offer the playback-speed control")
    }

    // MARK: - Scenario 04-G: Back navigation

    /// 04-G.1 — Tapping '← Back' from the player returns to the library.
    func testAudiobookPlayerBackNavigationReturnsToLibrary() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        XCTAssertTrue(audiobookTile.waitForExistence(timeout: 10))

        let backButton = openReader(from: audiobookTile, in: app)
        XCTAssertTrue(backButton.exists, "Player screen must open")
        backButton.tap()

        // After back-tap the nav stack unwinds and the library home must re-render. On a loaded
        // CI runner this can take >10s (the default); match the budget used by other nav waits.
        XCTAssertTrue(waitForLibraryHome(in: app), "Tapping back from the player should return to library home")
    }
}

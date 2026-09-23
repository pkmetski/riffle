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

        // All four pills render together when loading=false, but the CMP iOS accessibility bridge
        // populates the tree incrementally, so a pill can be visible before its node lands. Wait
        // for all four in ONE bounded expectation that polls until every pill is present, rather
        // than four compounding per-pill timeouts: the old 45s-each form could stack to 180s under
        // load and was the suite's single most frequent flake. This form caps the total pill wait
        // at 60s and passes the instant the tree settles.
        //
        // Label predicates (not testTag identifiers) because CMP does not expose sibling chips'
        // testTags to XCUITest — only the first element in a group propagates its identifier:
        //   - bookmarks: "0 bookmarks" (CONTAINS 'bookmark')
        //   - sleep: icon contentDescription "Sleep timer" + text "Sleep" (CONTAINS 'sleep')
        //   - speed: text "1×" (CONTAINS '×', U+00D7)
        let bookmarksPill = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'bookmark'")).firstMatch
        let sleepPill = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] 'sleep'")).firstMatch
        let speedPill = app.buttons.matching(NSPredicate(format: "label CONTAINS '×'")).firstMatch
        let allPillsPresent = NSPredicate { _, _ in
            self.chaptersPill.exists && bookmarksPill.exists && sleepPill.exists && speedPill.exists
        }
        let pillsResult = XCTWaiter.wait(
            for: [XCTNSPredicateExpectation(predicate: allPillsPresent, object: nil)],
            timeout: 60
        )
        XCTAssertEqual(pillsResult, .completed,
                       "Player must offer Chapters, bookmarks, sleep-timer and speed controls")
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

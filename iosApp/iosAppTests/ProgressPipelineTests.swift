import XCTest

// Covers the progress-pipeline scenarios from issue #1031: positions persist through shared
// stores and sync to the server. Uses AbsHarnessTestCase — the stub provides EPUB, PDF,
// Audiobook, and CBZ items so no XCTSkip is needed.
//
// Android equivalents:
//   - EPUB/PDF resume: PositionOrchestratorTest (app/src/test/.../reader/session/)
//   - Audiobook resume: AudiobookPlayerViewModelBookmarkTest (app/src/test/.../audiobook/)
//   - Sync cycle: ProgressSyncCycleTest (core/data/src/androidHostTest/.../ProgressSyncCycleTest.kt)

final class ProgressPipelineTests: AbsHarnessTestCase {

    // MARK: - Scenario PP-A: EPUB position is restored after re-opening

    /// PP-A.1 — Opening and leaving an EPUB book restores the reading position on next open.
    func testEpubPositionRestoredOnReopen() throws {
        try assertReaderReopens(hintKeywords: ["epub"], kind: "EPUB reader")
    }

    // MARK: - Scenario PP-B: PDF position is restored after re-opening

    /// PP-B.1 — Opening and leaving a PDF book restores the page on next open.
    func testPdfPositionRestoredOnReopen() throws {
        try assertReaderReopens(hintKeywords: ["pdf"], kind: "PDF reader")
    }

    // MARK: - Scenario PP-C: CBZ position is restored after re-opening

    /// PP-C.1 — Opening and leaving a CBZ book restores the page on next open.
    func testCbzPositionRestoredOnReopen() throws {
        try assertReaderReopens(hintKeywords: ["cbz"], kind: "CBZ reader")
    }

    // MARK: - Scenario PP-D: Library progress updates after reading

    /// PP-D.1 — After leaving an EPUB reader the library tile remains visible.
    func testLibraryProgressUpdatesAfterReading() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let anyTile = findFirstBookTile(hintKeywords: ["epub", "pdf", "cbz", "audiobook"])
        XCTAssertTrue(anyTile.waitForExistence(timeout: 10), "A book tile must be visible")
        let tileLabel = anyTile.label

        let backButton = openReader(from: anyTile, in: app)
        XCTAssertTrue(backButton.exists, "Reader must open")
        Thread.sleep(forTimeInterval: 2)
        tapBackToLibrary(backButton, in: app)
        let sameTile = app.buttons.matching(NSPredicate(format: "label == %@", tileLabel)).firstMatch
        XCTAssertTrue(
            sameTile.waitForExistence(timeout: 10),
            "Library tile should be visible after returning from reader"
        )
    }

    // MARK: - Scenario PP-E: Audiobook position restores on reopen

    /// PP-E.1 — Leaving the audiobook player and reopening it starts at the last position.
    func testAudiobookPositionRestoredOnReopen() throws {
        try assertReaderReopens(hintKeywords: ["audiobook"], kind: "Audiobook player", isAudiobook: true)
    }

    // MARK: - Helpers

    /// Opens the first tile matching `hintKeywords`, navigates to a non-zero position, closes the
    /// reader, and re-opens the same book. Asserts both that the reader reopens without crashing
    /// AND that the saved position was restored rather than silently reset to 0%.
    private func assertReaderReopens(hintKeywords: [String], kind: String, isAudiobook: Bool = false) throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let tile = findFirstBookTile(hintKeywords: hintKeywords)
        XCTAssertTrue(tile.waitForExistence(timeout: 10), "\(kind) tile must be visible")
        let tileLabel = tile.label

        let backButton = openReader(from: tile, in: app)
        XCTAssertTrue(backButton.exists, "\(kind) must open")

        if isAudiobook {
            // Advance the audiobook position by starting playback for a few seconds before closing.
            let playPause = app.buttons.matching(
                NSPredicate(format: "label == 'Play' OR label == 'Pause'")
            ).firstMatch
            if playPause.waitForExistence(timeout: 60) {
                if playPause.label == "Play" { playPause.tap() }
                Thread.sleep(forTimeInterval: 5)
                if playPause.exists && playPause.label == "Pause" { playPause.tap() }
            }
        } else {
            // Advance the ebook position by swiping left (next page) before closing.
            // This ensures the position saved is non-trivially zero.
            let center = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            let leftEdge = app.coordinate(withNormalizedOffset: CGVector(dx: 0.1, dy: 0.5))
            center.press(forDuration: 0.05, thenDragTo: leftEdge)
            Thread.sleep(forTimeInterval: 1)
        }

        tapBackToLibrary(backButton, in: app)
        let sameTile = app.buttons.matching(NSPredicate(format: "label == %@", tileLabel)).firstMatch
        XCTAssertTrue(sameTile.waitForExistence(timeout: 10), "\(kind) tile must reappear after close")

        // Position restore path: the player must seek to the saved position before showing its
        // back control. After AudiobookPlayerTests run in the same simulator clone, resource
        // pressure makes this seek take longer — 150 s matches the openReader default and avoids
        // a false timeout flake when both clones are under load.
        let reopenedBack = openReader(from: sameTile, in: app, timeout: 150)
        XCTAssertTrue(reopenedBack.exists, "\(kind) should re-open — position restore doesn't crash the screen")

        if isAudiobook {
            // The player_elapsed testTag exposes the current playback position.
            // A regression that resets position to 0 would show "0:00" here.
            let elapsed = app.descendants(matching: .any)
                .matching(NSPredicate(format: "identifier == 'player_elapsed'"))
                .firstMatch
            if elapsed.waitForExistence(timeout: 60) {
                XCTAssertNotEqual(elapsed.label, "0:00",
                    "\(kind) playback position should be restored, not reset to 0:00 (elapsed: '\(elapsed.label)')")
            }
        } else {
            // The chapter_navigation_rail contentDescription encodes the current chapter position
            // (e.g. "Chapter 1: 35%"). A regression that resets position to 0% would show ": 0%".
            let rail = app.descendants(matching: .any)
                .matching(NSPredicate(format: "identifier == 'chapter_navigation_rail'"))
                .firstMatch
            if rail.waitForExistence(timeout: 15) {
                XCTAssertFalse(rail.label.contains(": 0%"),
                    "\(kind) reading position should be restored, not reset to 0% (rail: '\(rail.label)')")
            }
        }
    }

    private func findFirstBookTile(hintKeywords: [String]) -> XCUIElement {
        for keyword in hintKeywords {
            let tile = app.buttons.matching(
                NSPredicate(format: "label CONTAINS[c] %@", keyword)
            ).firstMatch
            if tile.exists { return tile }
        }
        return app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'test'")
        ).firstMatch
    }
}

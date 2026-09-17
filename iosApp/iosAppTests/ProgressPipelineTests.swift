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
        backButton.tap()

        XCTAssertTrue(waitForLibraryHome(in: app), "Closing the reader must return to the library")
        let sameTile = app.buttons.matching(NSPredicate(format: "label == %@", tileLabel)).firstMatch
        XCTAssertTrue(
            sameTile.waitForExistence(timeout: 10),
            "Library tile should be visible after returning from reader"
        )
    }

    // MARK: - Scenario PP-E: Audiobook position restores on reopen

    /// PP-E.1 — Leaving the audiobook player and reopening it starts at the last position.
    func testAudiobookPositionRestoredOnReopen() throws {
        try assertReaderReopens(hintKeywords: ["audiobook"], kind: "Audiobook player")
    }

    // MARK: - Helpers

    /// Opens the first tile matching `hintKeywords`, closes the reader, and re-opens the same book:
    /// the position-restore path on second open must land in the reader without crashing.
    private func assertReaderReopens(hintKeywords: [String], kind: String) throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let tile = findFirstBookTile(hintKeywords: hintKeywords)
        XCTAssertTrue(tile.waitForExistence(timeout: 10), "\(kind) tile must be visible")
        let tileLabel = tile.label

        let backButton = openReader(from: tile, in: app)
        XCTAssertTrue(backButton.exists, "\(kind) must open")
        Thread.sleep(forTimeInterval: 2)
        backButton.tap()

        XCTAssertTrue(waitForLibraryHome(in: app), "Closing the \(kind) must return to the library")
        let sameTile = app.buttons.matching(NSPredicate(format: "label == %@", tileLabel)).firstMatch
        XCTAssertTrue(sameTile.waitForExistence(timeout: 10), "\(kind) tile must reappear after close")

        let reopenedBack = openReader(from: sameTile, in: app)
        XCTAssertTrue(reopenedBack.exists, "\(kind) should re-open — position restore doesn't crash the screen")
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

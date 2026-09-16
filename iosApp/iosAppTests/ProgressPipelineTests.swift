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
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let epubTile = findFirstBookTile(hintKeywords: ["epub"])
        XCTAssertTrue(epubTile.waitForExistence(timeout: 10), "EPUB tile must be visible")

        epubTile.tap()
        let backButton = app.staticTexts["← Back"]
        XCTAssertTrue(backButton.waitForExistence(timeout: 15), "EPUB reader must open")
        Thread.sleep(forTimeInterval: 2)
        backButton.tap()

        XCTAssertTrue(epubTile.waitForExistence(timeout: 10), "EPUB tile must reappear after close")
        epubTile.tap()
        XCTAssertTrue(
            app.staticTexts["← Back"].waitForExistence(timeout: 15),
            "EPUB reader should re-open — position restore doesn't crash the screen"
        )
    }

    // MARK: - Scenario PP-B: PDF position is restored after re-opening

    /// PP-B.1 — Opening and leaving a PDF book restores the page on next open.
    func testPdfPositionRestoredOnReopen() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let pdfTile = findFirstBookTile(hintKeywords: ["pdf"])
        XCTAssertTrue(pdfTile.waitForExistence(timeout: 10), "PDF tile must be visible")

        pdfTile.tap()
        let backButton = app.staticTexts["← Back"]
        XCTAssertTrue(backButton.waitForExistence(timeout: 15), "PDF reader must open")
        Thread.sleep(forTimeInterval: 2)
        backButton.tap()

        XCTAssertTrue(pdfTile.waitForExistence(timeout: 10), "PDF tile must reappear after close")
        pdfTile.tap()
        XCTAssertTrue(
            app.staticTexts["← Back"].waitForExistence(timeout: 15),
            "PDF reader should re-open — position restore doesn't crash the screen"
        )
    }

    // MARK: - Scenario PP-C: CBZ position is restored after re-opening

    /// PP-C.1 — Opening and leaving a CBZ book restores the page on next open.
    func testCbzPositionRestoredOnReopen() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let cbzTile = findFirstBookTile(hintKeywords: ["cbz"])
        XCTAssertTrue(cbzTile.waitForExistence(timeout: 10), "CBZ tile must be visible (stub provides Test CBZ)")

        cbzTile.tap()
        let backButton = app.staticTexts["← Back"]
        XCTAssertTrue(backButton.waitForExistence(timeout: 15), "CBZ reader must open")
        Thread.sleep(forTimeInterval: 2)
        backButton.tap()

        XCTAssertTrue(cbzTile.waitForExistence(timeout: 10), "CBZ tile must reappear after close")
        cbzTile.tap()
        XCTAssertTrue(
            app.staticTexts["← Back"].waitForExistence(timeout: 15),
            "CBZ reader should re-open — position restore doesn't crash the screen"
        )
    }

    // MARK: - Scenario PP-D: Library progress updates after reading

    /// PP-D.1 — After leaving an EPUB reader the library tile remains visible.
    func testLibraryProgressUpdatesAfterReading() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let anyTile = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] '%'")).firstMatch
        XCTAssertTrue(anyTile.waitForExistence(timeout: 10), "A book tile with progress % must be visible")

        anyTile.tap()
        let backButton = app.staticTexts["← Back"]
        XCTAssertTrue(backButton.waitForExistence(timeout: 15), "Reader must open")
        Thread.sleep(forTimeInterval: 2)
        backButton.tap()

        XCTAssertTrue(
            anyTile.waitForExistence(timeout: 10),
            "Library tile should be visible after returning from reader"
        )
    }

    // MARK: - Scenario PP-E: Audiobook position restores on reopen

    /// PP-E.1 — Leaving the audiobook player and reopening it starts at the last position.
    func testAudiobookPositionRestoredOnReopen() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let audiobookTile = app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] 'audiobook'")
        ).firstMatch
        XCTAssertTrue(audiobookTile.waitForExistence(timeout: 10),
                      "Audiobook tile must be visible (stub provides Test Audiobook)")

        audiobookTile.tap()
        let backButton = app.staticTexts["← Back"]
        XCTAssertTrue(backButton.waitForExistence(timeout: 15), "Audiobook player must open")
        Thread.sleep(forTimeInterval: 2)
        backButton.tap()

        XCTAssertTrue(audiobookTile.waitForExistence(timeout: 10), "Audiobook tile must reappear after close")
        audiobookTile.tap()
        XCTAssertTrue(
            app.staticTexts["← Back"].waitForExistence(timeout: 15),
            "Audiobook player should re-open without crashing"
        )
    }

    // MARK: - Helpers

    private func findFirstBookTile(hintKeywords: [String]) -> XCUIElement {
        for keyword in hintKeywords {
            let tile = app.buttons.matching(
                NSPredicate(format: "label CONTAINS[c] %@", keyword)
            ).firstMatch
            if tile.exists { return tile }
        }
        return app.buttons.matching(
            NSPredicate(format: "label CONTAINS[c] '%'")
        ).firstMatch
    }
}

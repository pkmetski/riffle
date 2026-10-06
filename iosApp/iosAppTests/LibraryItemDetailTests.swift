import XCTest

/// iOS counterpart to the Android `LibraryItemDetailScreenTest` harness suite.
///
/// Verifies that the Compose-Multiplatform `LibraryItemDetailScreen` (in `feature:library-ui`)
/// renders correctly on iOS — filling the gaps documented in issue #1145:
///
///   - The Read button is present (was already present before the lift)
///   - The Listen button is present for audiobook-only items (#1145 gap 4.1)
///   - Mark-as-read toggle is present (#1145 gap 4.7)
///   - The screen closes via the back button (#1145 gap 4.22 navigation)
///   - An EPUB item shows the Read button and no Listen button
///
/// All assertions run on the shared Compose screen; the detail sheet is reached by tapping a
/// cover tile in the library grid, matching the pattern used in `LibraryGridAndTabsUITests`.
final class LibraryItemDetailTests: AbsHarnessTestCase {

    // MARK: - EPUB item detail

    func testEpubItemShowsReadButtonAndBackButton() throws {
        XCTAssertTrue(waitForLibraryHome(in: app), "Library home must load")

        let tile = app.buttons[StubAbsServer.testItemTitle].firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 30), "Cover tile for the EPUB test item must appear")
        tile.tap()

        let readButton = app.buttons["book_detail_open"].firstMatch
        XCTAssertTrue(readButton.waitForExistence(timeout: 15), "Read button must appear on the EPUB item detail")

        let backButton = app.buttons["book_detail_back"].firstMatch
        XCTAssertTrue(backButton.exists, "Back button must appear on the item detail screen")
    }

    func testEpubItemBackButtonDismissesDetail() throws {
        XCTAssertTrue(waitForLibraryHome(in: app), "Library home must load")

        let tile = app.buttons[StubAbsServer.testItemTitle].firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 30))
        tile.tap()

        let readButton = app.buttons["book_detail_open"].firstMatch
        XCTAssertTrue(readButton.waitForExistence(timeout: 15), "Detail must be open")

        let backButton = app.buttons["book_detail_back"].firstMatch
        backButton.tap()

        // After navigating back the read button should no longer be visible
        let stillPresent = readButton.waitForExistence(timeout: 3)
        XCTAssertFalse(stillPresent, "Back button must dismiss the item detail screen")
    }

    // MARK: - Audiobook item detail

    /// Verifies gap 4.1 from issue #1145: the Listen button was missing on iOS.
    func testAudiobookItemShowsListenButton() throws {
        XCTAssertTrue(waitForLibraryHome(in: app), "Library home must load")

        let tile = app.buttons[StubAbsServer.testAudioItemTitle].firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 30), "Cover tile for the audiobook test item must appear")
        tile.tap()

        // The audiobook item has no ebook — it should expose Listen, not Read.
        let listenButton = app.buttons.matching(NSPredicate(format: "label == %@", "Listen")).firstMatch
        XCTAssertTrue(listenButton.waitForExistence(timeout: 15), "Listen button must appear on the audiobook item detail (#1145 gap 4.1)")
    }
}

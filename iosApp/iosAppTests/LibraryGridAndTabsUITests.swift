import XCTest

/// Full-app counterparts to two Android instrumentation suites, driven through the real
/// Compose-Multiplatform library shell on the simulator.
///
///   `app/src/androidTest/.../library/AdaptiveCoverGridTest.kt` — the cover grid is adaptive
///   `app/src/test/.../library/LibraryTabVisibilityTest.kt`     — tabs follow the data
///
/// The unit-level arithmetic lives in `LibraryCoverGridTests`; this file asserts that the grid the
/// user actually sees is laid out by it. Before `CoverGridLayout` was wired in, every iOS grid was
/// a fixed `GridCells.Adaptive(120.dp)`.
final class LibraryGridAndTabsUITests: AbsHarnessTestCase {

    /// Tab-bar entries are `NavigationBarItem`s whose icon carries the tab name as its content
    /// description. Compose-iOS surfaces that as the accessibility *label*, but the same string is
    /// also usable as an identifier on some element kinds, so match either.
    private func tab(_ name: String) -> XCUIElement {
        element(labelled: name)
    }

    private func element(labelled name: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == %@ OR label == %@", name, name))
            .firstMatch
    }

    // MARK: - Adaptive cover grid

    /// The All Books grid must lay out more than one cover per row on a phone. A regression that
    /// blew up the minimum cell size (or dropped the adaptive columns for a fixed single column)
    /// shows up here as one tile per row.
    ///
    /// Same technique as `AdaptiveCoverGridTest`: read the rendered tile frames and count how
    /// many share the topmost row.
    func testAllBooksGridLaysOutMultipleColumnsPerRow() throws {
        XCTAssertTrue(waitForLibraryHome(in: app, timeout: 30), "Library home must load")

        let allBooks = tab("All Books")
        XCTAssertTrue(allBooks.waitForExistence(timeout: 15), "The All Books tab must be present")
        allBooks.tap()

        // Cover tiles expose the book title as their accessibility label (BookCoverTile merges its
        // descendants under `contentDescription = item.title`).
        let titles = [
            StubAbsServer.testItemTitle,
            StubAbsServer.testStandaloneItemTitle,
            StubAbsServer.testPdfItemTitle,
            StubAbsServer.testFootnoteItemTitle,
        ]
        let firstTile = app.buttons[titles[0]].firstMatch
        XCTAssertTrue(firstTile.waitForExistence(timeout: 30), "The All Books grid must render cover tiles")

        var frames: [CGRect] = []
        for title in titles {
            let tile = app.buttons[title].firstMatch
            if tile.exists { frames.append(tile.frame) }
        }
        XCTAssertGreaterThanOrEqual(frames.count, 2, "Need at least two tiles to measure a row, got \(frames.count)")

        guard let topRowY = frames.map({ $0.minY }).min() else {
            return XCTFail("No tile frames were measurable")
        }
        // Tolerate sub-pixel differences between tiles that are genuinely on the same row.
        let inTopRow = frames.filter { abs($0.minY - topRowY) < 2 }
        XCTAssertGreaterThanOrEqual(
            inTopRow.count, 2,
            "The cover grid must place ≥ 2 covers on its first row; frames were \(frames.map { $0.minY })"
        )
    }

    // MARK: - Data-driven tab visibility

    /// The tab bar is derived from what the library actually holds, not a fixed list. The seeded
    /// stub has no annotations and no playlists, so those tabs must be absent while Home and
    /// All Books — which are unconditional — are present. A regression that hard-codes the bar
    /// would surface an Annotations tab onto an empty screen.
    func testTabBarOmitsTabsWithNoData() throws {
        XCTAssertTrue(waitForLibraryHome(in: app, timeout: 30), "Library home must load")

        XCTAssertTrue(tab("Home").waitForExistence(timeout: 15), "Home is always visible")
        XCTAssertTrue(tab("All Books").exists, "All Books is always visible")
        XCTAssertFalse(
            tab("Annotations").exists,
            "Annotations must stay hidden while the library has no annotated books"
        )
        XCTAssertFalse(
            tab("Playlists").exists,
            "Playlists must stay hidden while the source exposes no playlists"
        )
    }

    /// Switching to a tab must actually change the content, not just the selection highlight.
    func testSelectingAllBooksSwitchesAwayFromTheHomeSections() throws {
        XCTAssertTrue(waitForLibraryHome(in: app, timeout: 30), "Library home must load")
        // The Home tab shows section headers; All Books does not.
        let recentlyAdded = app.staticTexts["Recently Added"]
        XCTAssertTrue(recentlyAdded.waitForExistence(timeout: 20), "Home must show its section headers")

        tab("All Books").tap()
        XCTAssertTrue(
            recentlyAdded.waitForNonExistence(timeout: 15),
            "All Books must replace the sectioned home content"
        )

        tab("Home").tap()
        XCTAssertTrue(
            recentlyAdded.waitForExistence(timeout: 15),
            "Returning to Home must bring the sections back"
        )
    }
}

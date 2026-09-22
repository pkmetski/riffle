import XCTest

// Integration test suite for the Riffle iOS app — library browsing scenarios (issue #909).
// Uses AbsHarnessTestCase so the stub server is always configured; no XCTSkip.
final class IosAppTests: AbsHarnessTestCase {

    // MARK: - Helpers

    // Library home uses BasicText("Loading…"), not a UIActivityIndicator, so we must wait for
    // the loading text itself to disappear rather than relying on activityIndicators.
    private func waitForLibraryToLoad() {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)
        _ = app.staticTexts["Loading…"].waitForNonExistence(timeout: 30)
    }

    // MARK: - Scenario 1: Library Browsing (issue #909)

    /// 1.1 — Library home renders the section grid after launch.
    func testLibraryHomeSectionGridVisible() throws {
        waitForLibraryToLoad()

        let sectionLabels = ["In Progress", "Recently Added", "Finished", "Continue Series", "All Books"]
        let found = sectionLabels.contains { app.staticTexts[$0].waitForExistence(timeout: 10) }
        XCTAssertTrue(found, "At least one section header should be visible in the library home")
    }

    /// 1.3 + 1.4 — Tapping "See all" navigates to the section screen; back returns to library home.
    func testSeeAllNavigatesToSectionScreenAndBackReturns() throws {
        waitForLibraryToLoad()

        let seeAllButton = app.buttons["See all"].firstMatch
        XCTAssertTrue(seeAllButton.waitForExistence(timeout: 15), "'See all' button must be present")
        seeAllButton.tap()

        // BasicText+clickable in Compose maps to button trait on iOS; search all element types.
        let backButton = app.descendants(matching: .any).matching(
            NSPredicate(format: "label BEGINSWITH %@", "←")
        ).firstMatch
        XCTAssertTrue(
            backButton.waitForExistence(timeout: 15),
            "Back button should appear after navigating into a section screen"
        )
        backButton.tap()

        let sectionLabels = ["In Progress", "Recently Added", "Finished", "Continue Series", "All Books"]
        let backOnHome = sectionLabels.contains { app.staticTexts[$0].waitForExistence(timeout: 5) }
        XCTAssertTrue(backOnHome, "Navigating back should return to the library home screen")
    }

    // MARK: - Scenario 4: Series and Collection Detail (issue #916)

    /// 4.1 + 4.2 — Tapping a series tile navigates to series detail; back returns to library.
    func testSeriesTileNavigatesToDetailAndBackReturns() throws {
        waitForLibraryToLoad()

        // Scroll until the series tile is hittable — one swipeUp on the header may not be enough.
        let seriesTile = app.buttons[StubAbsServer.testSeriesName].firstMatch
        XCTAssertTrue(seriesTile.waitForExistence(timeout: 15), "Series tile must appear in library")
        for _ in 0..<5 {
            if seriesTile.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(seriesTile.isHittable, "Series tile must be hittable before tap")
        seriesTile.tap()

        let backArrow = app.descendants(matching: .any).matching(
            NSPredicate(format: "label BEGINSWITH %@", "←")
        ).firstMatch
        XCTAssertTrue(
            backArrow.waitForExistence(timeout: 15),
            "Series detail screen should show a back arrow"
        )
        backArrow.tap()

        let anySection = ["In Progress", "Recently Added", "Finished", "Continue Series", "All Books", "Series"].contains {
            app.staticTexts[$0].waitForExistence(timeout: 5)
        }
        XCTAssertTrue(anySection, "Navigating back from series detail should return to library home")
    }

    /// 4.3 + 4.4 — Tapping a collection tile navigates to collection detail; back returns to library.
    func testCollectionTileNavigatesToDetailAndBackReturns() throws {
        waitForLibraryToLoad()

        // Reached through the Collections tab rather than the Home tab's Collections shelf.
        // That shelf is the last item of a LazyColumn, so whether it is composed at all depends
        // on exactly where the scroll lands — it happened to fit on the first screenful until
        // the cover tiles grew a title/author caption, and scrolling to it is unreliable because
        // a vertical drag starting over one of the horizontal cover shelves is swallowed by that
        // `LazyRow`. The tab is the canonical way into a collection and is always present. The
        // claim is unchanged: a collection is reachable in the library and tapping it opens
        // collection detail.
        let collectionsTab = app.buttons["Collections"].firstMatch
        XCTAssertTrue(collectionsTab.waitForExistence(timeout: 15), "Collections tab must be present")
        collectionsTab.tap()

        let collectionTile = app.buttons[StubAbsServer.testCollectionName].firstMatch
        XCTAssertTrue(collectionTile.waitForExistence(timeout: 15), "Collection tile must appear in library")
        XCTAssertTrue(collectionTile.isHittable, "Collection tile must be hittable before tap")
        collectionTile.tap()

        let backArrow = app.descendants(matching: .any).matching(
            NSPredicate(format: "label BEGINSWITH %@", "←")
        ).firstMatch
        XCTAssertTrue(
            backArrow.waitForExistence(timeout: 15),
            "Collection detail screen should show a back arrow"
        )
        backArrow.tap()

        let anySection = ["In Progress", "Recently Added", "Finished", "Continue Series", "All Books", "Collections"].contains {
            app.staticTexts[$0].waitForExistence(timeout: 5)
        }
        XCTAssertTrue(anySection, "Navigating back from collection detail should return to library home")
    }

    // MARK: - Scenario 4.5 — Item tap in Series detail opens item detail

    /// 4.5 — Tapping a book tile inside a series detail screen opens the item detail screen.
    func testItemTapInSeriesDetailNavigatesToItemDetail() throws {
        waitForLibraryToLoad()

        let seriesTile2 = app.buttons[StubAbsServer.testSeriesName].firstMatch
        XCTAssertTrue(seriesTile2.waitForExistence(timeout: 15), "Series tile must appear in library")
        for _ in 0..<5 {
            if seriesTile2.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(seriesTile2.isHittable, "Series tile must be hittable before tap")
        seriesTile2.tap()

        let seriesDetailBack = app.descendants(matching: .any).matching(
            NSPredicate(format: "label BEGINSWITH %@", "←")
        ).firstMatch
        XCTAssertTrue(seriesDetailBack.waitForExistence(timeout: 15), "Series detail must open")

        let itemTile = app.buttons[StubAbsServer.testItemTitle].firstMatch
        XCTAssertTrue(itemTile.waitForExistence(timeout: 15), "An item tile must be visible in series detail")
        itemTile.tap()

        let itemDetailBack = app.descendants(matching: .any).matching(
            NSPredicate(format: "label BEGINSWITH %@", "←")
        ).firstMatch
        XCTAssertTrue(
            itemDetailBack.waitForExistence(timeout: 15),
            "Tapping a book in series detail must open item detail (← Back button)"
        )
    }

    // MARK: - Scenario 4.6 — Item tap in Collection detail opens item detail

    /// 4.6 — Tapping a book tile inside a collection detail screen opens the item detail screen.
    func testItemTapInCollectionDetailNavigatesToItemDetail() throws {
        waitForLibraryToLoad()

        // Reached through the Collections tab rather than the Home tab's Collections shelf.
        // That shelf is the last item of a LazyColumn, so whether it is composed at all depends
        // on exactly where the scroll lands — it happened to fit on the first screenful until
        // the cover tiles grew a title/author caption, and scrolling to it is unreliable because
        // a vertical drag starting over one of the horizontal cover shelves is swallowed by that
        // `LazyRow`. The tab is the canonical way into a collection and is always present. The
        // claim is unchanged: a collection is reachable in the library and tapping it opens
        // collection detail.
        let collectionsTab = app.buttons["Collections"].firstMatch
        XCTAssertTrue(collectionsTab.waitForExistence(timeout: 15), "Collections tab must be present")
        collectionsTab.tap()

        let collectionTile2 = app.buttons[StubAbsServer.testCollectionName].firstMatch
        XCTAssertTrue(collectionTile2.waitForExistence(timeout: 15), "Collection tile must appear in library")
        XCTAssertTrue(collectionTile2.isHittable, "Collection tile must be hittable before tap")
        collectionTile2.tap()

        let collectionDetailBack = app.descendants(matching: .any).matching(
            NSPredicate(format: "label BEGINSWITH %@", "←")
        ).firstMatch
        XCTAssertTrue(collectionDetailBack.waitForExistence(timeout: 15), "Collection detail must open")

        let itemTile = app.buttons[StubAbsServer.testItemTitle].firstMatch
        XCTAssertTrue(itemTile.waitForExistence(timeout: 15), "An item tile must be visible in collection detail")
        itemTile.tap()

        let itemDetailBack = app.descendants(matching: .any).matching(
            NSPredicate(format: "label BEGINSWITH %@", "←")
        ).firstMatch
        XCTAssertTrue(
            itemDetailBack.waitForExistence(timeout: 30),
            "Tapping a book in collection detail must open item detail (← Back button)"
        )
    }
}

import XCTest

// Integration test suite for the Riffle iOS app — library browsing scenarios (issue #909).
// Uses AbsHarnessTestCase so the stub server is always configured; no XCTSkip.
final class IosAppTests: AbsHarnessTestCase {

    // MARK: - Scenario 1: Library Browsing (issue #909)

    /// 1.1 — Library home renders the section grid after launch.
    func testLibraryHomeSectionGridVisible() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let sectionLabels = ["In Progress", "Recently Added", "Finished", "Continue Series", "All Books"]
        let found = sectionLabels.contains { app.staticTexts[$0].exists }
        XCTAssertTrue(found, "At least one section header should be visible in the library home")
    }

    /// 1.3 + 1.4 — Tapping "See all" navigates to the section screen; back returns to library home.
    func testSeeAllNavigatesToSectionScreenAndBackReturns() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let seeAllButton = app.buttons["See all"].firstMatch
        XCTAssertTrue(seeAllButton.waitForExistence(timeout: 10), "'See all' button must be present")
        seeAllButton.tap()

        let backButton = app.buttons["Back"].firstMatch
        XCTAssertTrue(
            backButton.waitForExistence(timeout: 5),
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
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let seriesHeader = app.staticTexts["Series"]
        XCTAssertTrue(seriesHeader.waitForExistence(timeout: 10), "Series section must be visible")

        let seriesTile = app.buttons.matching(NSPredicate(format: "NOT label IN %@",
            ["Series", "Collections", "All Books", "In Progress", "Recently Added", "Finished", "Continue Series", "See all"]
        )).firstMatch
        XCTAssertTrue(seriesTile.waitForExistence(timeout: 5), "A series tile must be visible")
        seriesTile.tap()

        let backArrow = app.staticTexts["←"].firstMatch
        XCTAssertTrue(
            backArrow.waitForExistence(timeout: 5),
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
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let collectionsHeader = app.staticTexts["Collections"]
        XCTAssertTrue(collectionsHeader.waitForExistence(timeout: 10), "Collections section must be visible")

        collectionsHeader.swipeUp()
        let collectionTile = app.buttons.matching(NSPredicate(format: "NOT label IN %@",
            ["Series", "Collections", "All Books", "In Progress", "Recently Added", "Finished", "Continue Series", "See all"]
        )).firstMatch
        XCTAssertTrue(collectionTile.waitForExistence(timeout: 5), "A collection tile must be visible")
        collectionTile.tap()

        let backArrow = app.staticTexts["←"].firstMatch
        XCTAssertTrue(
            backArrow.waitForExistence(timeout: 5),
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
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        XCTAssertTrue(app.staticTexts["Series"].waitForExistence(timeout: 10), "Series section must be visible")

        let seriesTile = app.buttons.matching(NSPredicate(format: "NOT label IN %@",
            ["Series", "Collections", "All Books", "In Progress", "Recently Added", "Finished", "Continue Series", "See all",
             "Open menu", "Home", "To Read", "Annotations", "Playlists"]
        )).firstMatch
        XCTAssertTrue(seriesTile.waitForExistence(timeout: 5), "A series tile must be visible")
        seriesTile.tap()

        let backArrow = app.staticTexts["←"].firstMatch
        XCTAssertTrue(backArrow.waitForExistence(timeout: 5), "Series detail must open")

        let itemTile = app.buttons.matching(NSPredicate(format: "NOT label IN %@",
            ["←", "See all"]
        )).firstMatch
        XCTAssertTrue(itemTile.waitForExistence(timeout: 5), "An item tile must be visible in series detail")
        itemTile.tap()

        let itemDetailBack = app.buttons["← Back"].firstMatch
        XCTAssertTrue(
            itemDetailBack.waitForExistence(timeout: 10),
            "Tapping a book in series detail must open item detail (← Back button)"
        )
    }

    // MARK: - Scenario 4.6 — Item tap in Collection detail opens item detail

    /// 4.6 — Tapping a book tile inside a collection detail screen opens the item detail screen.
    func testItemTapInCollectionDetailNavigatesToItemDetail() throws {
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        XCTAssertTrue(app.staticTexts["Collections"].waitForExistence(timeout: 10), "Collections section must be visible")

        let collectionTile = app.buttons.matching(NSPredicate(format: "NOT label IN %@",
            ["Series", "Collections", "All Books", "In Progress", "Recently Added", "Finished", "Continue Series", "See all",
             "Open menu", "Home", "To Read", "Annotations", "Playlists"]
        )).firstMatch
        XCTAssertTrue(collectionTile.waitForExistence(timeout: 5), "A collection tile must be visible")
        collectionTile.tap()

        let backArrow = app.staticTexts["←"].firstMatch
        XCTAssertTrue(backArrow.waitForExistence(timeout: 5), "Collection detail must open")

        let itemTile = app.buttons.matching(NSPredicate(format: "NOT label IN %@",
            ["←", "See all"]
        )).firstMatch
        XCTAssertTrue(itemTile.waitForExistence(timeout: 5), "An item tile must be visible in collection detail")
        itemTile.tap()

        let itemDetailBack = app.buttons["← Back"].firstMatch
        XCTAssertTrue(
            itemDetailBack.waitForExistence(timeout: 10),
            "Tapping a book in collection detail must open item detail (← Back button)"
        )
    }
}

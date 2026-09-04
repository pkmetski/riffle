import XCTest

// Integration test suite for the Riffle iOS app.
// Tests that require a live server skip automatically in CI when no source is configured.
final class IosAppTests: XCTestCase {

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

    // MARK: - Scenario 1: Library Browsing (issue #909)

    /// 1.1 — Library home renders the section grid after launch.
    func testLibraryHomeSectionGridVisible() throws {
        // If no source is configured, the app shows the setup screen — skip.
        if app.staticTexts["Add a source to get started"].waitForExistence(timeout: 5) {
            throw XCTSkip("No source configured — library home test requires a connected server")
        }
        if app.staticTexts["No libraries found"].waitForExistence(timeout: 3) {
            throw XCTSkip("No libraries found — library home test requires a server with libraries")
        }

        // Wait for the loading spinner to disappear (library items loaded)
        let spinner = app.activityIndicators.firstMatch
        let spinnerGone = spinner.waitForNonExistence(timeout: 15)
        XCTAssertTrue(spinnerGone, "Loading spinner should disappear once library items are loaded")

        // At least one section header label is visible
        let sectionLabels = ["In Progress", "Recently Added", "Finished", "Continue Series", "All Books"]
        let found = sectionLabels.contains { label in
            app.staticTexts[label].exists
        }
        XCTAssertTrue(found, "At least one section header should be visible in the library home")
    }

    /// 1.3 + 1.4 — Tapping "See all" navigates to the section screen; back returns to library home.
    func testSeeAllNavigatesToSectionScreenAndBackReturns() throws {
        // If no source is configured, skip.
        if app.staticTexts["Add a source to get started"].waitForExistence(timeout: 5) {
            throw XCTSkip("No source configured — section navigation test requires a connected server")
        }
        if app.staticTexts["No libraries found"].waitForExistence(timeout: 3) {
            throw XCTSkip("No libraries found — section navigation test requires a server with libraries")
        }

        // Wait for library to load
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        // Find a "See all" button
        let seeAllButton = app.buttons["See all"].firstMatch
        guard seeAllButton.waitForExistence(timeout: 5) else {
            throw XCTSkip("No 'See all' button visible — library may have no sections with items")
        }
        seeAllButton.tap()

        // A section screen should appear with a back button
        let backButton = app.buttons["Back"].firstMatch
        XCTAssertTrue(
            backButton.waitForExistence(timeout: 5),
            "Back button should appear after navigating into a section screen"
        )

        // Navigate back
        backButton.tap()

        // Library name should be visible in the TopAppBar again
        let sectionLabels = ["In Progress", "Recently Added", "Finished", "Continue Series", "All Books"]
        let backOnHome = sectionLabels.contains { app.staticTexts[$0].waitForExistence(timeout: 5) }
        XCTAssertTrue(backOnHome, "Navigating back should return to the library home screen")
    }

    // MARK: - Scenario 4: Series and Collection Detail (issue #916)

    /// 4.1 + 4.2 — Tapping a series tile navigates to series detail; back returns to library.
    func testSeriesTileNavigatesToDetailAndBackReturns() throws {
        if app.staticTexts["Add a source to get started"].waitForExistence(timeout: 5) {
            throw XCTSkip("No source configured — series detail test requires a connected server")
        }
        if app.staticTexts["No libraries found"].waitForExistence(timeout: 3) {
            throw XCTSkip("No libraries found — series detail test requires a server with libraries")
        }

        // Wait for library to load
        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let seriesHeader = app.staticTexts["Series"]
        guard seriesHeader.waitForExistence(timeout: 5) else {
            throw XCTSkip("No Series section visible — library may have no series")
        }

        // Tap the first series tile (any button in the row below "Series" header)
        let seriesTile = app.buttons.matching(NSPredicate(format: "NOT label IN %@",
            ["Series", "Collections", "All Books", "In Progress", "Recently Added", "Finished", "Continue Series", "See all"]
        )).firstMatch
        guard seriesTile.waitForExistence(timeout: 5) else {
            throw XCTSkip("No tappable series tile found")
        }
        seriesTile.tap()

        // Series detail screen should show a back arrow
        let backArrow = app.staticTexts["←"].firstMatch
        XCTAssertTrue(
            backArrow.waitForExistence(timeout: 5),
            "Series detail screen should show a back arrow"
        )

        // Tap back
        backArrow.tap()

        // Library home should reappear
        let anySection = ["In Progress", "Recently Added", "Finished", "Continue Series", "All Books", "Series"].contains {
            app.staticTexts[$0].waitForExistence(timeout: 5)
        }
        XCTAssertTrue(anySection, "Navigating back from series detail should return to library home")
    }

    /// 4.3 + 4.4 — Tapping a collection tile navigates to collection detail; back returns to library.
    func testCollectionTileNavigatesToDetailAndBackReturns() throws {
        if app.staticTexts["Add a source to get started"].waitForExistence(timeout: 5) {
            throw XCTSkip("No source configured — collection detail test requires a connected server")
        }
        if app.staticTexts["No libraries found"].waitForExistence(timeout: 3) {
            throw XCTSkip("No libraries found — collection detail test requires a server with libraries")
        }

        _ = app.activityIndicators.firstMatch.waitForNonExistence(timeout: 15)

        let collectionsHeader = app.staticTexts["Collections"]
        guard collectionsHeader.waitForExistence(timeout: 5) else {
            throw XCTSkip("No Collections section visible — library may have no collections")
        }

        // Scroll to Collections section and tap the first tile
        collectionsHeader.swipeUp()
        let collectionTile = app.buttons.matching(NSPredicate(format: "NOT label IN %@",
            ["Series", "Collections", "All Books", "In Progress", "Recently Added", "Finished", "Continue Series", "See all"]
        )).firstMatch
        guard collectionTile.waitForExistence(timeout: 5) else {
            throw XCTSkip("No tappable collection tile found")
        }
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
}

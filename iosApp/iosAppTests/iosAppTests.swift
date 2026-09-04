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
}

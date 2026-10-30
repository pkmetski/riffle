import XCTest

final class AnnotationSearchFlowTests: XCTestCase {

    var app: XCUIApplication!
    var absServer: StubAbsServer!

    override func setUpWithError() throws {
        continueAfterFailure = false
        absServer = StubAbsServer()
        absServer.start()

        app = XCUIApplication()
        app.launchArguments += [
            "--RIFFLE_RESET_FOR_TESTS",
            seedSourceArgument(type: "ABS", url: absServer.baseUrl, username: "testuser", password: "test"),
            "--RIFFLE_SEED_ANNOTATIONS=6|\(StubAbsServer.testStandaloneItemId)",
        ]
        app.launch()

        waitForSeededLibraryHome(in: app, sourceName: "Audiobookshelf")
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
        absServer.shutdown()
        absServer = nil
    }

    // MARK: - Tests

    func testShowAllAnnotationsOpensResultsScreen() {
        let searchField = app.descendants(matching: .any)
            .matching(identifier: RiffleTestTags.librarySearch)
            .firstMatch
        XCTAssertTrue(searchField.waitForExistence(timeout: 10),
                      "Library search bar must be visible on the library home screen")

        searchField.tap()
        searchField.typeText("section text")

        // Dismiss the software keyboard so the full results list is unobscured.
        // With the keyboard open the "Show all" button sits below the visible area
        // (y ≈ 594 on an 852-pt screen vs. ~516 pt of usable space), so any tap
        // aimed at the button's coordinates lands on the keyboard instead.
        let returnKey = app.keyboards.buttons["return"].firstMatch
        if returnKey.waitForExistence(timeout: 3) { returnKey.tap() }

        let showAllButton = app.descendants(matching: .any)
            .matching(identifier: RiffleTestTags.showAllAnnotations)
            .firstMatch
        XCTAssertTrue(showAllButton.waitForExistence(timeout: 30),
                      "Show all annotations button must appear in search results")
        // Compose LazyColumn does not implement kAXScrollToVisibleAction, so a plain .tap()
        // fails with kAXErrorCannotComplete. Tap via element-relative coordinate instead.
        showAllButton.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()

        let resultsScreen = app.descendants(matching: .any)
            .matching(identifier: RiffleTestTags.annotationSearchResults)
            .firstMatch
        XCTAssertTrue(resultsScreen.waitForExistence(timeout: 30),
                      "Annotation search results screen must appear after tapping Show all annotations")
    }
}

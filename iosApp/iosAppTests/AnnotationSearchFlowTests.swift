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

        let showAllButton = app.descendants(matching: .any)
            .matching(identifier: RiffleTestTags.showAllAnnotations)
            .firstMatch
        XCTAssertTrue(showAllButton.waitForExistence(timeout: 10),
                      "Show all annotations button must appear in search results")
        showAllButton.tap()

        let resultsScreen = app.descendants(matching: .any)
            .matching(identifier: RiffleTestTags.annotationSearchResults)
            .firstMatch
        XCTAssertTrue(resultsScreen.waitForExistence(timeout: 15),
                      "Annotation search results screen must appear after tapping Show all annotations")
    }
}

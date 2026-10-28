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

    func testAnnotationSearchFieldOpensResultsScreen() {
        let annotationsTab = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'Annotations' OR label == 'Annotations'"))
            .firstMatch
        XCTAssertTrue(annotationsTab.waitForExistence(timeout: 20),
                      "Annotations tab must be visible after seeding 6 annotations")
        annotationsTab.tap()

        let searchField = app.descendants(matching: .any)
            .matching(identifier: RiffleTestTags.annotationsSearchField)
            .firstMatch
        XCTAssertTrue(searchField.waitForExistence(timeout: 10),
                      "Annotation search field must appear on the Annotations tab")

        searchField.tap()
        searchField.typeText("section text")

        let submitButton = app.descendants(matching: .any)
            .matching(identifier: RiffleTestTags.annotationSearchSubmit)
            .firstMatch
        XCTAssertTrue(submitButton.waitForExistence(timeout: 5),
                      "Annotation search submit button must be present")
        submitButton.tap()

        let resultsScreen = app.descendants(matching: .any)
            .matching(identifier: RiffleTestTags.annotationSearchResults)
            .firstMatch
        XCTAssertTrue(resultsScreen.waitForExistence(timeout: 15),
                      "Annotation search results screen must appear after submitting a query")
    }
}

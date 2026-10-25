import XCTest

/// Regression test for issue #1311: annotation search navigation works on iOS.
///
/// Android counterpart: `ShowAllAnnotationsHarnessTest.kt` — covers the library-search-bar
/// "Show all annotations" path. iOS reaches the same results screen via the Annotations-tab
/// search field (`TestTags.ANNOTATIONS_SEARCH_FIELD` / "annotation-search-field") because iOS
/// has no global library search bar.
///
/// Setup: seeds 6 highlight annotations via `--RIFFLE_SEED_ANNOTATIONS=6|item-test-2` so the
/// Annotations tab becomes visible (it is hidden when the library has no annotated books).
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

    /// Tapping the Annotations-tab search field, entering a query, and submitting navigates to
    /// the annotation search results screen (accessibilityIdentifier "annotation-search-results").
    func testAnnotationSearchFieldOpensResultsScreen() {
        let annotationsTab = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'Annotations' OR label == 'Annotations'"))
            .firstMatch
        XCTAssertTrue(annotationsTab.waitForExistence(timeout: 20),
                      "Annotations tab must be visible after seeding 6 annotations")
        annotationsTab.tap()

        let searchField = app.descendants(matching: .any)
            .matching(identifier: "annotation-search-field")
            .firstMatch
        XCTAssertTrue(searchField.waitForExistence(timeout: 10),
                      "Annotation search field must appear on the Annotations tab")

        searchField.tap()
        searchField.typeText("section text")

        let submitButton = app.descendants(matching: .any)
            .matching(identifier: "annotation-search-submit")
            .firstMatch
        XCTAssertTrue(submitButton.waitForExistence(timeout: 5),
                      "Annotation search submit button must be present")
        submitButton.tap()

        let resultsScreen = app.descendants(matching: .any)
            .matching(identifier: "annotation-search-results")
            .firstMatch
        XCTAssertTrue(resultsScreen.waitForExistence(timeout: 15),
                      "Annotation search results screen must appear after submitting a query")
    }
}

import XCTest

// iOS counterpart to Android's back-stack navigation assertions from issue #1184.
//
// Android:
//   - NavController back-stack is managed by NavHost; Back on AddSource pops to source picker.
//   - LibraryHost navStack keyed by libraryId resets when the library changes.
//
// iOS:
//   - Source-setup flow uses a Kotlin mutable-state navStack in iosMain; Back pops it.
//   - LibraryHost's navStack is now `remember(libraryId) { … }` so it resets on library change.
//
// These tests verify the user-visible claim — that Back actually navigates backward — by driving
// iOS's own Compose Multiplatform rendering through XCUIApplication. Server not required;
// --RIFFLE_RESET_FOR_TESTS forces the first-run onboarding path where source-setup nav is active.
final class LibraryNavStackTests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // No source configured — app opens on the first-run source-type picker.
        app.launchArguments += ["--RIFFLE_RESET_FOR_TESTS"]
        app.launch()
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
    }

    /// Back from the Add Source form returns to the source-type picker (stack pops).
    ///
    /// Android counterpart: SourceTypePickerScreenTest.backFromAddSource_returnsToSourcePicker
    func testBackFromAddSourceReturnsToSourceTypePicker() throws {
        XCTAssertTrue(
            app.staticTexts["Add source"].waitForExistence(timeout: 60),
            "App must open on the source-type picker in first-run mode"
        )

        let absCard = app.staticTexts["Audiobookshelf"]
        XCTAssertTrue(absCard.waitForExistence(timeout: 10), "ABS card must appear on picker")

        let hittable = NSPredicate(format: "hittable == true")
        wait(for: [XCTNSPredicateExpectation(predicate: hittable, object: absCard)], timeout: 10)
        absCard.tap()

        XCTAssertTrue(
            app.staticTexts["Add Audiobookshelf"].waitForExistence(timeout: 10),
            "Add Audiobookshelf form must appear after tapping the ABS card"
        )

        // nav_back maps from TestTags.NAV_BACK via the Compose → accessibilityIdentifier bridge.
        let backButton = app.buttons.matching(identifier: "nav_back").firstMatch
        XCTAssertTrue(backButton.waitForExistence(timeout: 5), "Back button must be present on Add Source form")
        backButton.tap()

        // Stack must have popped — source-type picker is visible again.
        XCTAssertTrue(
            app.staticTexts["Add source"].waitForExistence(timeout: 10),
            "Source-type picker must reappear after popping the Add Source form"
        )
        XCTAssertFalse(
            app.staticTexts["Add Audiobookshelf"].exists,
            "Add Audiobookshelf form must not remain after Back"
        )
    }
}

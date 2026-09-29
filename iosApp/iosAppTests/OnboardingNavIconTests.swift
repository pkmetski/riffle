import XCTest

// iOS counterpart to SourceTypePickerScreenTest.backButton_hiddenWhenShowNavigationIconFalse
// (app/src/androidTest/…/SourceTypePickerScreenTest.kt).
//
// During first-run onboarding (no source configured) the SourceOnboardingHost passes
// canNavigateBack=false to SourceTypePickerScreen and AddSourceScreen, hiding the nav_back button
// because there is nowhere to navigate back to. This test drives iOS's own code path through
// the Compose Multiplatform rendering layer.
final class OnboardingNavIconTests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // Fresh install — no source seeded, so the app opens on the first-run onboarding picker.
        app.launchArguments += ["--RIFFLE_RESET_FOR_TESTS"]
        app.launch()
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
    }

    /// The back button must not appear on the source-type picker during first-run onboarding.
    func testBackButtonAbsentOnPickerDuringOnboarding() throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 60),
                      "App must start on the source picker in first-run mode")

        // nav_back is the accessibilityIdentifier mapped from TestTags.NAV_BACK.
        let backButton = app.buttons.matching(identifier: "nav_back").firstMatch
        XCTAssertFalse(backButton.exists,
                       "Back button must not be present on the picker during first-run onboarding")
    }

    /// The back button must not appear on the AddSource screen during first-run onboarding.
    func testBackButtonAbsentOnAddSourceDuringOnboarding() throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 60),
                      "App must start on the source picker in first-run mode")

        let absCard = app.staticTexts["Audiobookshelf"]
        XCTAssertTrue(absCard.waitForExistence(timeout: 10), "ABS card must be visible")
        let hittable = NSPredicate(format: "hittable == true")
        wait(for: [XCTNSPredicateExpectation(predicate: hittable, object: absCard)], timeout: 10)
        absCard.tap()

        XCTAssertTrue(app.staticTexts["Add Audiobookshelf"].waitForExistence(timeout: 10),
                      "Add Audiobookshelf screen must appear after tapping the card")

        let backButton = app.buttons.matching(identifier: "nav_back").firstMatch
        XCTAssertFalse(backButton.exists,
                       "Back button must not be present on AddSource screen during first-run onboarding")
    }
}

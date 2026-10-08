import XCTest

// iOS counterpart to the Back/stack navigation parity items in issue #1184.
//
// The key behavioral claims verified here:
// 1. With no sources configured, the nav-stack root is the source-type picker — not a blank screen
//    or a library list. The stack has a correct initial destination.
// 2. Tapping a source type pushes its setup screen onto the stack (forward push works correctly),
//    and the picker is no longer visible — the push fully replaces the visible destination.
//
// These tests use --RIFFLE_RESET_FOR_TESTS so no live server is needed.
// Back from AddSource during first-run onboarding is intentionally absent (nav_back is hidden
// because there is nowhere to go back to) — that claim is covered by OnboardingNavIconTests.
final class LibraryNavStackTests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        // Fresh install — no source configured, so the app opens on the first-run source picker.
        app.launchArguments += ["--RIFFLE_RESET_FOR_TESTS"]
        app.launch()
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
    }

    /// The nav-stack root with no sources configured is the source-type picker, not a blank screen.
    /// The picker has no nav_back button because it IS the root.
    func testNoSourcesConfigured_stackRootIsSourceTypePicker() throws {
        XCTAssertTrue(
            app.staticTexts["Add source"].waitForExistence(timeout: 60),
            "App must open on the source-type picker when no sources are configured"
        )
        // nav_back maps from TestTags.NAV_BACK via the Compose → accessibilityIdentifier bridge.
        let backButton = app.buttons.matching(identifier: "nav_back").firstMatch
        XCTAssertFalse(backButton.exists, "Stack root (source-type picker) must not have a back button")
    }

    /// Tapping a source-type card pushes its setup screen (forward nav push works).
    /// The picker must not remain visible once a destination is pushed on top.
    func testTappingSourceTypeCard_pushesSetupScreen() throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 60),
                      "App must open on the source-type picker")

        let absCard = app.staticTexts["Audiobookshelf"]
        XCTAssertTrue(absCard.waitForExistence(timeout: 10), "ABS card must appear on picker")

        let hittable = NSPredicate(format: "hittable == true")
        wait(for: [XCTNSPredicateExpectation(predicate: hittable, object: absCard)], timeout: 10)
        absCard.tap()

        XCTAssertTrue(
            app.staticTexts["Add Audiobookshelf"].waitForExistence(timeout: 15),
            "Add Audiobookshelf setup screen must appear after tapping the ABS card (stack push)"
        )
        // The picker must no longer be the top of the stack.
        XCTAssertFalse(app.staticTexts["Add source"].exists,
                       "Source-type picker must not be visible while setup screen is on top")
    }
}

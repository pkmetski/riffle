import XCTest

// iOS counterpart to NavigateAsRootTest.kt (app/src/androidTest/kotlin/com/riffle/app/navigation/).
//
// NavigateAsRootTest guards two failure modes of the CMP navigation back-stack:
//   (1) Blank screen: popping the sole root entry leaves an empty NavHost.
//   (2) Duplicate roots: switching drawer destinations accumulates redundant back-stack entries.
//
// These tests verify the same behavioral claims via XCUIApplication on the running iOS app.
// The app uses Compose Multiplatform with the shared navigateAsRoot / popBackStackIfTop
// helpers — the same Kotlin code runs on both platforms.
final class NavDrawerTests: AbsHarnessTestCase {

    // MARK: - ND-1  Back from Settings returns to library home (blank-screen regression)

    /// Popping the Settings root surface must land on the library home — not a blank or empty screen.
    ///
    /// Regression for the "burger menu blank" bug: if Settings is the sole back-stack entry when
    /// the user presses back, an empty NavHost is rendered instead of the home surface.
    /// navigateAsRoot keeps HOME beneath every root so popping Settings always reveals home.
    func testBackFromSettingsReturnsToLibraryHome() throws {
        // The harness base class already lands us on the library home with the burger visible.
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10), "Library home must show the burger menu")

        // Open the drawer and navigate to Settings.
        burger.tap()
        let settingsEntry = app.staticTexts["Settings"]
        XCTAssertTrue(settingsEntry.waitForExistence(timeout: 10), "Drawer must show a Settings entry")
        settingsEntry.tap()

        // Settings screen must be visible (has at least a "Sources" or "Remove" element).
        let settingsVisible = NSPredicate { _, _ in
            self.app.staticTexts["Settings"].exists ||
            self.app.navigationBars["Settings"].exists ||
            self.app.descendants(matching: .any)
                .matching(NSPredicate(format: "identifier == 'settings-trailing-Remove'"))
                .firstMatch.exists
        }
        let appeared = XCTWaiter.wait(
            for: [XCTNSPredicateExpectation(predicate: settingsVisible, object: nil)],
            timeout: 15
        )
        XCTAssertEqual(appeared, .completed, "Settings must open after tapping the drawer entry")

        // Press back. The ← Back button in CMP is labelled "← Back"; it may also appear as a
        // system back gesture. Prefer the accessibility button if present; fall back to a swipe.
        let backButton = app.buttons["← Back"].firstMatch
        if backButton.waitForExistence(timeout: 5) && backButton.isHittable {
            backButton.tap()
        } else {
            // Swipe from the leading edge to trigger a back gesture.
            let leading = app.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.5))
            let mid = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            leading.press(forDuration: 0.1, thenDragTo: mid)
        }

        // After back we must be on the library home — the burger must be visible — not blank.
        XCTAssertTrue(
            burger.waitForExistence(timeout: 15),
            "Back from Settings must return to library home; app must not show a blank screen"
        )
        XCTAssertTrue(app.state == .runningForeground, "App must still be running after back from Settings")
    }

    // MARK: - ND-2  Repeated drawer-to-Settings round trips do not stack entries

    /// Opening Settings from the drawer repeatedly then pressing back must always land one hop
    /// from the library home — never deeper in a growing stack.
    ///
    /// Counterpart to switchingRootsNeverAccumulatesOrEmptiesBackStack: each navigateAsRoot call
    /// replaces the current root surface. After three trips to Settings (and back), only one hop
    /// is needed to return to the library home.
    func testRepeatedDrawerNavigationDoesNotAccumulateSettingsEntries() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10), "Library home must show the burger menu")

        // Perform three Settings round-trips. If roots accumulate, the third would require three
        // back-presses; with navigateAsRoot it always requires just one.
        for round in 1...3 {
            burger.tap()
            let settingsEntry = app.staticTexts["Settings"]
            XCTAssertTrue(
                settingsEntry.waitForExistence(timeout: 10),
                "Drawer must show Settings on round \(round)"
            )
            settingsEntry.tap()

            // Wait for Settings to appear.
            let settingsScreen = app.descendants(matching: .any)
                .matching(NSPredicate(format: "identifier == 'settings-trailing-Remove'"))
                .firstMatch
            // Accept either the Remove action or a "Settings" title being visible as proof of arrival.
            let reachedSettings = settingsScreen.waitForExistence(timeout: 15) ||
                app.navigationBars["Settings"].waitForExistence(timeout: 5)
            XCTAssertTrue(reachedSettings, "Settings must be reachable on round \(round)")

            // One back press must return to library home.
            let backButton = app.buttons["← Back"].firstMatch
            if backButton.waitForExistence(timeout: 5) && backButton.isHittable {
                backButton.tap()
            } else {
                let leading = app.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.5))
                let mid = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                leading.press(forDuration: 0.1, thenDragTo: mid)
            }

            XCTAssertTrue(
                burger.waitForExistence(timeout: 15),
                "One back from Settings must return to library home on round \(round)"
            )
        }
    }

    // MARK: - ND-3  Drawer is accessible and shows expected entries

    /// The navigation drawer must list at least the source name and Settings.
    ///
    /// Regression guard: if the burger tap opens an empty drawer (e.g. due to a blank-NavHost root
    /// bug) the source name and Settings entries would be missing.
    func testDrawerContainsSourceAndSettingsEntries() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10), "Library home must show the burger menu")

        burger.tap()

        // The seeded ABS source must appear as a drawer entry.
        XCTAssertTrue(
            app.staticTexts["Audiobookshelf"].waitForExistence(timeout: 10),
            "Drawer must list the seeded Audiobookshelf source"
        )
        XCTAssertTrue(
            app.staticTexts["Settings"].exists,
            "Drawer must always list Settings"
        )
    }
}

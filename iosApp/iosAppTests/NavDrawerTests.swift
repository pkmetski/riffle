import XCTest

// iOS counterparts to the Android navigation-drawer suite:
//
//   app/src/androidTest/.../navigation/NavigateAsRootTest.kt       (blank screen / duplicate roots)
//   app/src/test/.../feature/navigation/NavigationDrawerSourceSubtitleTest.kt (host subtitle)
//   app/src/test/.../feature/navigation/NavigationDrawerViewModelTest.kt      (library listing)
//
// Important: iOS does NOT share Android's navigation helpers. Android's MainScreen drives a
// `NavController` and guards it with `navigateAsRoot` / `popBackStackIfTop` /
// `shouldInterceptBackForDrawer`, all of which live in `app/src/main/kotlin` and are Android-only.
// iOS's HomeScreen.kt instead switches on a `rememberSaveable` `AppSection` enum plus a per-library
// `LibraryNav` state, so the back-stack bug class those helpers guard cannot occur there and their
// unit tests have nothing to port. What IS portable is the user-visible claim — "back from
// Settings lands on the library home, never on a blank screen, and never one hop deeper each
// time" — so these drive iOS's own implementation through XCUIApplication.
final class NavDrawerTests: AbsHarnessTestCase {

    /// iOS's Settings screen labels its back affordance "← Libraries" (SettingsScreen.kt's back
    /// header); the nested panels and the reader use "← Back". These three tests had never
    /// executed — NavDrawerTests.swift was a member of no Xcode target until this change — so the
    /// original "← Back" lookup silently fell through to an edge swipe that does nothing in
    /// Compose Multiplatform, and the screen never went back.
    private func leaveSettings() {
        for label in ["← Libraries", "← Back"] {
            let control = app.buttons[label].firstMatch
            if control.waitForExistence(timeout: 5) && control.isHittable {
                control.tap()
                return
            }
        }
        XCTFail("The Settings screen must offer a tappable back control")
    }

    // MARK: - ND-1  Back from Settings returns to library home (blank-screen regression)

    /// Popping the Settings root surface must land on the library home — not a blank or empty screen.
    ///
    /// Same user-visible claim as Android's "burger menu blank" regression, against iOS's own
    /// implementation: leaving the Settings section must restore the library surface underneath
    /// it rather than an empty screen.
    func testBackFromSettingsReturnsToLibraryHome() throws {
        // The harness base class already lands us on the library home with the burger visible.
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10), "Library home must show the burger menu")

        // Open the drawer and navigate to Settings.
        burger.tap()
        let settingsEntry = app.staticTexts["Settings"]
        XCTAssertTrue(settingsEntry.waitForExistence(timeout: 10), "Drawer must show a Settings entry")
        settingsEntry.tap()

        // The Settings screen is identified by its own back header, which only that screen shows —
        // the word "Settings" alone is ambiguous with the drawer entry that is still on screen
        // while the drawer animates shut.
        XCTAssertTrue(
            app.buttons["← Libraries"].waitForExistence(timeout: 15),
            "Settings must open after tapping the drawer entry"
        )

        leaveSettings()

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
    /// Counterpart to switchingRootsNeverAccumulatesOrEmptiesBackStack. iOS switches an
    /// `AppSection` enum rather than pushing nav entries, so the claim here is that the section
    /// switch stays idempotent: after three trips to Settings, one back still lands on home.
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

            XCTAssertTrue(
                app.buttons["← Libraries"].waitForExistence(timeout: 15),
                "Settings must be reachable on round \(round)"
            )

            // One back press must return to library home.
            leaveSettings()

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

    // MARK: - ND-4  Source switcher header carries the host as its subtitle

    /// Android's `NavigationDrawerSourceSubtitleTest` pins that a credentialed source shows its
    /// host under the display name so a user with two Audiobookshelf installs can tell them
    /// apart. iOS builds the same line in `HomeScreen.DrawerSheetContent`; assert it renders the
    /// stub's real authority rather than an empty or placeholder subtitle.
    func testDrawerHeaderShowsTheSourceHostBeneathItsName() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10), "Library home must show the burger menu")
        burger.tap()

        XCTAssertTrue(
            app.staticTexts["Audiobookshelf"].waitForExistence(timeout: 10),
            "Drawer must name the active source"
        )

        let expectedHost = URL(string: absServer.baseUrl)
            .flatMap { url -> String? in
                guard let host = url.host else { return nil }
                return url.port.map { "\(host):\($0)" } ?? host
            }
        let host = try XCTUnwrap(expectedHost, "The stub server must expose a host:port base URL")
        XCTAssertTrue(
            app.staticTexts[host].waitForExistence(timeout: 10),
            "Drawer must show the source host '\(host)' as the switcher subtitle"
        )
    }

    // MARK: - ND-5  Source switcher is collapsed until tapped

    /// The switcher starts collapsed — the drawer opens on the library list, not on a source
    /// picker. Tapping the header expands it, which is what the caret flip encodes.
    func testSourceSwitcherStartsCollapsedAndExpandsOnTap() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10))
        burger.tap()

        let collapsed = app.staticTexts["▼ Switch source"]
        XCTAssertTrue(collapsed.waitForExistence(timeout: 10), "Switcher must start collapsed")
        XCTAssertFalse(app.staticTexts["▲ Switch source"].exists)

        collapsed.tap()
        XCTAssertTrue(
            app.staticTexts["▲ Switch source"].waitForExistence(timeout: 10),
            "Tapping the header must expand the source switcher"
        )
    }

    // MARK: - ND-6  Drawer lists every visible library and switching one re-titles the screen

    /// The drawer's library list is the only way to move between an ABS source's libraries.
    /// The stub serves two; selecting the second must close the drawer and re-title the library
    /// screen — a silently ignored tap (or a list that only ever renders the active library) is
    /// the regression this catches.
    func testDrawerListsBothLibrariesAndSwitchingRetitlesTheScreen() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10))
        burger.tap()

        let secondLibrary = app.staticTexts[StubAbsServer.testLibraryName2]
        XCTAssertTrue(
            app.staticTexts[StubAbsServer.testLibraryName].waitForExistence(timeout: 15),
            "Drawer must list the first library"
        )
        XCTAssertTrue(secondLibrary.exists, "Drawer must list every visible library, not just the active one")

        secondLibrary.tap()
        XCTAssertTrue(
            app.staticTexts[StubAbsServer.testLibraryName2].waitForExistence(timeout: 20),
            "Selecting a library must re-title the library screen"
        )
        XCTAssertTrue(burger.waitForExistence(timeout: 15), "The drawer must close back onto the library screen")
    }

    // MARK: - ND-7  Downloads is reachable from the drawer

    /// `NavigationDrawerViewModelTest`'s `showDownloadsLink` tests pin that the Downloads
    /// destination is offered. iOS lists it unconditionally; assert the entry exists and actually
    /// navigates rather than being a dead row.
    func testDrawerOffersDownloadsAndItOpens() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10))
        burger.tap()

        let downloads = app.staticTexts["Downloads"]
        XCTAssertTrue(downloads.waitForExistence(timeout: 10), "Drawer must list Downloads")
        downloads.tap()

        let back = app.buttons["← Back"].firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 15), "Downloads must open its own screen with a back control")
        back.tap()
        XCTAssertTrue(burger.waitForExistence(timeout: 15), "Back from Downloads must return to the library home")
    }
}

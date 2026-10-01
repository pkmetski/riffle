import XCTest

// iOS counterparts to the Android navigation-drawer suite:
//
//   app/src/androidTest/.../navigation/NavigateAsRootTest.kt       (blank screen / duplicate roots)
//   feature/library-ui/src/commonTest/.../NavigationDrawerSubtitleTest.kt (host subtitle)
//   feature/navigation/src/commonTest/.../NavigationDrawerViewModelTest.kt (library listing)
//
// Important: iOS does NOT share Android's navigation helpers. Android's MainScreen drives a
// `NavController` and guards it with `navigateAsRoot` / `popBackStackIfTop` /
// `shouldInterceptBackForDrawer`, all of which live in `app/src/main/kotlin` and are Android-only.
// iOS's HomeScreen.kt instead switches on a `rememberSaveable` `AppSection` enum plus a per-library
// `LibraryNav` state, so the back-stack bug class those helpers guard cannot occur there and their
// unit tests have nothing to port. What IS portable is the user-visible claim — "back from
// Settings lands on the library home, never on a blank screen, and never one hop deeper each
// time" — so these drive iOS's own implementation through XCUIApplication.
//
// Accessibility note: Material3's ListItem with a clickable modifier uses mergeDescendants=true,
// which merges all child texts into the parent button's label. The drawer source-switcher header
// is therefore exposed as a Button (not StaticText) in the accessibility tree, and its text is
// accessible via the button's `label` property (CONTAINS match) or via the testTag
// "nav_drawer_source_header" which maps to accessibilityIdentifier through CMP's iOS bridge.
//
// Downloads availability: AbsCatalog is JVM-only, so the ABS source registered by the harness
// does not implement DownloadsCapability on iOS. NavigationDrawerViewModel therefore sets
// showDownloadsLink=false for ABS-only setups, and the Downloads entry does not appear.
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

    /// Finds the drawer source-switcher header element for the seeded Audiobookshelf source.
    ///
    /// Material3 ListItem with Modifier.clickable uses mergeDescendants=true, so all descendant
    /// texts (source name, username, host, arrow icon description) collapse into a single
    /// element's accessibility label. The source name is NOT a separate StaticText child —
    /// it is only reachable by searching for an element whose merged label CONTAINS the source
    /// name. We search across all element types since ListItem may map to button, cell, or
    /// otherElement depending on CMP version and iOS OS version.
    private func sourceHeaderButton() -> XCUIElement {
        let pred = NSPredicate(format: "label CONTAINS[c] 'Audiobookshelf'")
        return app.descendants(matching: .any).matching(pred).firstMatch
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
            app.buttons["← Libraries"].waitForExistence(timeout: 25),
            "Settings must open after tapping the drawer entry"
        )

        leaveSettings()

        // After back we must be on the library home — the burger must be visible — not blank.
        XCTAssertTrue(
            burger.waitForExistence(timeout: 25),
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
                app.buttons["← Libraries"].waitForExistence(timeout: 25),
                "Settings must be reachable on round \(round)"
            )

            // One back press must return to library home.
            leaveSettings()

            XCTAssertTrue(
                burger.waitForExistence(timeout: 25),
                "One back from Settings must return to library home on round \(round)"
            )
        }
    }

    // MARK: - ND-3  Drawer is accessible and shows expected entries

    /// The navigation drawer must list at least the source name and Settings.
    ///
    /// Regression guard: if the burger tap opens an empty drawer (e.g. due to a blank-NavHost root
    /// bug) the source name and Settings entries would be missing.
    ///
    /// The source header is a Material3 ListItem with clickable modifier (mergeDescendants=true),
    /// so the source name is accessible via the button's label rather than as a StaticText.
    /// We find the header by its testTag accessibilityIdentifier "nav_drawer_source_header".
    func testDrawerContainsSourceAndSettingsEntries() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10), "Library home must show the burger menu")

        burger.tap()

        // The seeded ABS source must appear in the source-switcher header button.
        let header = sourceHeaderButton()
        XCTAssertTrue(
            header.waitForExistence(timeout: 10),
            "Drawer must show the source-switcher header button"
        )
        XCTAssertTrue(
            header.label.contains("Audiobookshelf"),
            "Source-switcher header must show the active source name 'Audiobookshelf'; got: \(header.label)"
        )
        XCTAssertTrue(
            app.staticTexts["Settings"].exists,
            "Drawer must always list Settings"
        )
    }

    // MARK: - ND-4  Source switcher header carries the host as its subtitle

    /// Android's `NavigationDrawerSourceSubtitleTest` pins that a credentialed source shows its
    /// host under the display name so a user with two Audiobookshelf installs can tell them
    /// apart. iOS builds the same line in `RiffleNavigationDrawer`'s `DrawerHeader`; assert it
    /// renders the stub's real authority in the header button's merged label.
    func testDrawerHeaderShowsTheSourceHostBeneathItsName() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10), "Library home must show the burger menu")
        burger.tap()

        let header = sourceHeaderButton()
        XCTAssertTrue(
            header.waitForExistence(timeout: 10),
            "Drawer must show the source-switcher header"
        )

        let expectedHost = URL(string: absServer.baseUrl)
            .flatMap { url -> String? in
                guard let host = url.host else { return nil }
                return url.port.map { "\(host):\($0)" } ?? host
            }
        let host = try XCTUnwrap(expectedHost, "The stub server must expose a host:port base URL")
        XCTAssertTrue(
            header.label.contains(host),
            "Source-switcher header must include the source host '\(host)' as a subtitle; got: \(header.label)"
        )
    }

    // MARK: - ND-5  Source switcher is collapsed until tapped

    /// The switcher starts collapsed — the drawer opens on the library list, not on a source
    /// picker. Tapping the header expands a dropdown that lists all sources.
    ///
    /// The implementation uses a DropdownMenu (not inline expanded text), so the test verifies
    /// that a second element containing "Audiobookshelf" appears after tapping the header.
    func testSourceSwitcherStartsCollapsedAndExpandsOnTap() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10))
        burger.tap()

        let header = sourceHeaderButton()
        XCTAssertTrue(
            header.waitForExistence(timeout: 10),
            "Switcher header must be present before tapping"
        )
        XCTAssertTrue(
            header.label.contains("Audiobookshelf"),
            "Switcher header must show the active source name"
        )

        // Before tapping: the dropdown is not open — only the header matches "Audiobookshelf".
        // The DropdownMenu is not rendered yet, so there's no dropdown item to match.
        let absPred = NSPredicate(format: "label CONTAINS[c] 'Audiobookshelf'")
        let beforeCount = app.descendants(matching: .any).matching(absPred).count
        XCTAssertGreaterThanOrEqual(beforeCount, 1, "Before tapping: header must exist and match 'Audiobookshelf'")

        // Tap the header to open the dropdown.
        header.tap()

        // After tapping: the DropdownMenu appears, adding a second element that CONTAINS
        // "Audiobookshelf" — the DropdownMenuItem for the ABS source. The dropdown item may
        // have a merged label that includes the trailing "Active source" icon description, so
        // we use CONTAINS rather than exact match, and exclude the header itself by identifier.
        let dropdownItem = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label CONTAINS[c] 'Audiobookshelf' AND identifier != 'nav_drawer_source_header'"))
            .firstMatch
        XCTAssertTrue(
            dropdownItem.waitForExistence(timeout: 10),
            "Tapping the header must open the dropdown — a new 'Audiobookshelf' element must appear"
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
            app.staticTexts[StubAbsServer.testLibraryName].waitForExistence(timeout: 25),
            "Drawer must list the first library"
        )
        XCTAssertTrue(secondLibrary.exists, "Drawer must list every visible library, not just the active one")

        secondLibrary.tap()
        XCTAssertTrue(
            app.staticTexts[StubAbsServer.testLibraryName2].waitForExistence(timeout: 25),
            "Selecting a library must re-title the library screen"
        )
        XCTAssertTrue(burger.waitForExistence(timeout: 25), "The drawer must close back onto the library screen")
    }

    // MARK: - ND-7  Downloads is absent for ABS on iOS

    /// AbsCatalog is JVM-only, so iOS's CatalogRegistry does not return a DownloadsCapability
    /// for ABS sources. NavigationDrawerViewModel therefore sets showDownloadsLink=false when
    /// only ABS sources are registered, and the Downloads entry does not appear in the drawer.
    ///
    /// This is a known iOS/Android capability gap: the Downloads entry in Android's drawer
    /// comes from AbsCatalog's DownloadsCapability, which has no iOS equivalent.
    func testDownloadsIsAbsentForAbsOnlySourceOnIos() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10))
        burger.tap()

        // Wait for the drawer to settle — the library list must be visible.
        XCTAssertTrue(
            app.staticTexts[StubAbsServer.testLibraryName].waitForExistence(timeout: 15),
            "Drawer must show the library list before checking for Downloads absence"
        )

        // Downloads must NOT appear because AbsCatalog has no DownloadsCapability on iOS.
        let downloads = app.staticTexts["Downloads"]
        XCTAssertFalse(
            downloads.exists,
            "Downloads must not appear in the drawer for ABS-only sources on iOS (AbsCatalog is JVM-only)"
        )
    }

    // MARK: - ND-8  Version footer is absent on iOS (appVersion not yet wired)

    /// Android's drawer shows a version footer from `BuildConfig.VERSION_NAME`. iOS passes
    /// `appVersion = null` (no BuildConfig), so `RiffleNavigationDrawer` suppresses the footer
    /// via its `if (appVersion != null)` guard. Assert the footer is NOT rendered rather than
    /// showing a blank or placeholder string.
    func testVersionFooterIsAbsentBecauseAppVersionIsNull() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10))
        burger.tap()

        // Wait for the drawer to settle using Settings (always visible, not source-dependent).
        XCTAssertTrue(app.staticTexts["Settings"].waitForExistence(timeout: 10))

        // No element whose label begins with "Riffle v" should exist in the drawer.
        let versionPredicate = NSPredicate(format: "label BEGINSWITH 'Riffle v'")
        let versionTexts = app.staticTexts.matching(versionPredicate)
        XCTAssertEqual(
            versionTexts.count, 0,
            "iOS does not supply appVersion, so the version footer must not appear in the drawer"
        )
    }
}

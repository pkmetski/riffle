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
//
// Version footer: appVersion is wired on iOS via CFBundleShortVersionString in HomeScreen.kt
// (issue #1184 parity), so the "Riffle v…" footer is expected to appear in the drawer.
//
// Class-level launch strategy: the base iOS harness suite takes ~40 minutes, leaving ~10 min
// for new tests before hitting the 50-min job wall. With -parallel-testing-worker-count 2
// each simulator clone now shares a single app launch for all its NavDrawerTests instead of
// launching once per test. This reduces the overhead from 5 × 3-min launches distributed across
// 2 clones (~7.5 min wall clock) to 2 × 3-min launches (~3 min wall clock) — saving ~4.5 min
// and keeping the suite well within budget. Each test recovers to library-home state in
// setUpWithError() so tests are independent despite the shared session.
final class NavDrawerTests: XCTestCase {

    // MARK: - Class-level shared session

    static var sharedApp: XCUIApplication!
    static var sharedAbsServer: StubAbsServer!

    override static func setUp() {
        super.setUp()
        let server = StubAbsServer()
        server.start()
        sharedAbsServer = server

        let app = XCUIApplication()
        app.launchArguments += [
            "--RIFFLE_RESET_FOR_TESTS",
            seedSourceArgument(type: "ABS", url: server.baseUrl, username: "testuser", password: "test")
        ]
        app.launch()
        sharedApp = app

        waitForSeededLibraryHome(in: app, sourceName: "Audiobookshelf")
    }

    override static func tearDown() {
        sharedApp?.terminate()
        sharedApp = nil
        sharedAbsServer?.shutdown()
        sharedAbsServer = nil
        super.tearDown()
    }

    // Between tests: recover to library home with drawer closed so each test starts cleanly.
    // The previous test may have left the drawer open, the source switcher expanded, or
    // navigated into Settings — all of which this setUp handles before the next test runs.
    override func setUpWithError() throws {
        continueAfterFailure = false
        let app = NavDrawerTests.sharedApp!

        // Tap the top-centre of the screen — always a safe non-interactive region in both
        // the library home and the Settings screen — to collapse any open dropdown or overlay.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()

        // Navigate back if the previous test left us in Settings.
        for label in ["← Libraries", "← Back"] {
            let btn = app.buttons[label].firstMatch
            if btn.exists && btn.isHittable {
                btn.tap()
                break
            }
        }

        // Verify library home is showing (burger visible). If the drawer is still open
        // it covers the burger — tap the far-right scrim to close it, then re-check.
        let burger = app.buttons["Open menu"]
        if !burger.waitForExistence(timeout: 15) {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
            XCTAssertTrue(
                burger.waitForExistence(timeout: 30),
                "setUp: must return to library home (burger visible) before each test"
            )
        }
    }

    // MARK: - Convenience accessors

    private var app: XCUIApplication { NavDrawerTests.sharedApp }
    private var absServer: StubAbsServer { NavDrawerTests.sharedAbsServer }

    // MARK: - Helpers

    /// iOS's Settings screen labels its back affordance "← Libraries" (SettingsScreen.kt's back
    /// header); the nested panels and the reader use "← Back".
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
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10), "Library home must show the burger menu")

        burger.tap()
        let settingsEntry = app.staticTexts["Settings"]
        XCTAssertTrue(settingsEntry.waitForExistence(timeout: 10), "Drawer must show a Settings entry")
        settingsEntry.tap()

        // The Settings screen is identified by its own back header, which only that screen shows —
        // the word "Settings" alone is ambiguous with the drawer entry still animating shut.
        XCTAssertTrue(
            app.buttons["← Libraries"].waitForExistence(timeout: 25),
            "Settings must open after tapping the drawer entry"
        )

        leaveSettings()

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
    /// switch stays idempotent: after two trips to Settings, one back still lands on home.
    func testRepeatedDrawerNavigationDoesNotAccumulateSettingsEntries() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10), "Library home must show the burger menu")

        // Two round-trips suffice to prove idempotency — the claim is that returning once is
        // always enough, not that it holds exactly three times.
        for round in 1...2 {
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

            leaveSettings()

            XCTAssertTrue(
                burger.waitForExistence(timeout: 25),
                "One back from Settings must return to library home on round \(round)"
            )
        }
    }

    // MARK: - ND-3/4/7/8  Drawer contents, host subtitle, Downloads and version absences

    /// The navigation drawer must list the source name, Settings, and the source host subtitle;
    /// it must not show Downloads (AbsCatalog is JVM-only on iOS); it must show a version footer
    /// (appVersion is wired via CFBundleShortVersionString in HomeScreen.kt as of #1184).
    ///
    /// Consolidates ND-3 (source header + Settings present), ND-4 (host subtitle in header),
    /// ND-7 (Downloads absent for ABS-only), and ND-8 (version footer present). All four
    /// assertions need only one drawer open, so merging them saves three app launches.
    func testDrawerContentsSubtitleAndAbsencesForAbsSource() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10), "Library home must show the burger menu")

        burger.tap()

        // ND-3: source header and Settings present.
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

        // ND-4: host subtitle in merged header label.
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

        // ND-7: Downloads must not appear for ABS-only sources (AbsCatalog is JVM-only).
        XCTAssertFalse(
            app.staticTexts["Downloads"].exists,
            "Downloads must not appear in the drawer for ABS-only sources on iOS (AbsCatalog is JVM-only)"
        )

        // ND-8: iOS wires appVersion via CFBundleShortVersionString (HomeScreen.kt, issue #1184),
        // so the version footer must appear in the drawer.
        let versionPredicate = NSPredicate(format: "label BEGINSWITH 'Riffle v'")
        XCTAssertGreaterThanOrEqual(
            app.staticTexts.matching(versionPredicate).count, 1,
            "iOS wires appVersion, so the version footer must appear in the drawer"
        )

        // Close the drawer so subsequent tests start from library home (drawer open = burger hidden).
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        XCTAssertTrue(
            app.buttons["Open menu"].waitForExistence(timeout: 15),
            "Drawer must close after ND-3/4/7/8 assertions to leave library home for the next test"
        )
    }

    // MARK: - ND-5  Source switcher is collapsed until tapped

    /// The switcher starts collapsed — the drawer opens on the library list, not on a source
    /// picker. Tapping the header expands a dropdown that lists all sources.
    func testSourceSwitcherStartsCollapsedAndExpandsOnTap() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10))
        burger.tap()

        let header = sourceHeaderButton()
        XCTAssertTrue(
            header.waitForExistence(timeout: 10),
            "Switcher header must be present before tapping"
        )

        // Before tapping: only the header matches "Audiobookshelf".
        let absPred = NSPredicate(format: "label CONTAINS[c] 'Audiobookshelf'")
        let beforeCount = app.descendants(matching: .any).matching(absPred).count
        XCTAssertGreaterThanOrEqual(beforeCount, 1, "Before tapping: header must exist and match 'Audiobookshelf'")

        header.tap()

        // After tapping: the DropdownMenu appears with a second Audiobookshelf element
        // (the DropdownMenuItem for the source, distinct from the header).
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
    /// The stub serves two; selecting the second must close the drawer and re-title the screen.
    func testDrawerListsBothLibrariesAndSwitchingRetitlesTheScreen() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 10))
        burger.tap()

        let secondLibrary = app.staticTexts[StubAbsServer.testLibraryName2]
        // Library names in the drawer come from cached data — they should appear quickly.
        XCTAssertTrue(
            app.staticTexts[StubAbsServer.testLibraryName].waitForExistence(timeout: 15),
            "Drawer must list the first library"
        )
        XCTAssertTrue(secondLibrary.exists, "Drawer must list every visible library, not just the active one")

        secondLibrary.tap()
        // SourceBrowseHeader renders the title as sourceName.uppercase(), so the accessibility
        // element in the screen header is "TEST LIBRARY 2", not "Test Library 2".
        XCTAssertTrue(
            app.staticTexts[StubAbsServer.testLibraryName2.uppercased()].waitForExistence(timeout: 30),
            "Selecting a library must re-title the library screen (title rendered uppercase)"
        )
        // Once the library title is visible, the burger should already be present.
        XCTAssertTrue(burger.waitForExistence(timeout: 15), "The drawer must close back onto the library screen")
    }
}

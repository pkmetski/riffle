import XCTest

// Smoke tests for the shared AppShell wired via HomeScreen.kt.
//
// After the #1250 refactor, HomeScreen.kt became a thin wrapper that creates AppShellSlots
// and delegates entirely to the shared AppShell composable in :feature:shell-ui.  These
// tests verify that the iOS slot wiring is correct: the navigation drawer opens, and the
// top-level sections reachable from the drawer (Settings, Downloads) still render.
//
// Downloads: AbsCatalog is JVM-only so NavigationDrawerViewModel sets showDownloadsLink=false
// for ABS-only setups on iOS. The drawer entry therefore won't appear for an ABS source; we
// verify the drawer itself opens and Settings is always present.
final class AppShellSmokeTest: XCTestCase {

    private var app: XCUIApplication!
    private var absServer: StubAbsServer!

    override func setUpWithError() throws {
        continueAfterFailure = false

        absServer = StubAbsServer()
        absServer.start()

        app = XCUIApplication()
        app.launchArguments += [
            "--RIFFLE_RESET_FOR_TESTS",
            seedSourceArgument(type: "ABS", url: absServer.baseUrl, username: "testuser", password: "test")
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

    // MARK: - AS-1  Navigation drawer can be opened

    /// The hamburger button must open the navigation drawer.
    ///
    /// Verifies that AppShell correctly wires the RiffleNavigationDrawer and its open/close
    /// callback reaches the drawer state through the iOS slot.
    func testNavigationDrawerOpens() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(
            burger.waitForExistence(timeout: 30),
            "Library home must show the hamburger 'Open menu' button"
        )

        burger.tap()

        let settingsEntry = app.staticTexts["Settings"]
        XCTAssertTrue(
            settingsEntry.waitForExistence(timeout: 15),
            "Navigation drawer must open and show the Settings entry"
        )
    }

    // MARK: - AS-2  Settings section is reachable via drawer

    /// Tapping Settings in the drawer must open the Settings screen.
    ///
    /// Verifies the AppShellSlots.platformSettingsHooks (IosPlatformSettingsHooks) and the
    /// Settings section routing in AppShell both work correctly on iOS.
    func testSettingsReachableFromDrawer() throws {
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 30), "Burger must be visible at library home")
        burger.tap()

        let settingsEntry = app.staticTexts["Settings"]
        XCTAssertTrue(
            settingsEntry.waitForExistence(timeout: 15),
            "Drawer must show the Settings entry"
        )
        settingsEntry.tap()

        XCTAssertTrue(
            app.buttons["← Libraries"].waitForExistence(timeout: 25),
            "Tapping Settings must open the Settings screen (identified by its back button)"
        )
    }
}

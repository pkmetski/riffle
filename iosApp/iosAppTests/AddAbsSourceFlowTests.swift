import XCTest

// End-to-end coverage for the add-Audiobookshelf-source flow (scenario 17-source-picker).
// Uses StubAbsServer so the test always has a reachable server — no XCTSkip.
final class AddAbsSourceFlowTests: XCTestCase {

    private var app: XCUIApplication!
    private var absServer: StubAbsServer!

    override func setUpWithError() throws {
        continueAfterFailure = false
        absServer = StubAbsServer()
        absServer.start()
        app = XCUIApplication()
        app.launchArguments += ["--RIFFLE_RESET_FOR_TESTS"]
        app.launch()
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
        absServer.shutdown()
        absServer = nil
    }

    // MARK: - Chitanka

    /// Chitanka is a zero-config catalogue iOS cannot browse, so #1071 §17 gated it out of the
    /// picker: installing it used to write the source and its library rows and return Success,
    /// leaving the user in a permanently empty library with no error.
    ///
    /// This replaces `testChitankaInstallDoesNotCrash`, which pinned the 2026-09-07 install crash
    /// (IosLogger routed messages through NSLog varargs, which segfaults on Kotlin/Native). That
    /// claim is not lost: the crash was in the shared install path, which the Audiobookshelf and
    /// Komga end-to-end tests still drive on every run. What cannot be asserted any more is the
    /// Chitanka install itself, because there no longer is one.
    func testChitankaIsOfferedButNotInstallable() throws {
        // Cold-launch budget. This is the first thing a harness test does on a fresh app,
        // and on CI it competes with the other simulator clone; issue #1066 grew the suite
        // from 29 to 39 tests across the same two clones and pushed the old 40s past the
        // edge for whichever test runs first in its suite. Later tests in the same class
        // reach this in under 10s because the app is warm.
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 120),
                      "App must start on the source picker")

        let card = app.staticTexts["Chitanka"]
        XCTAssertTrue(card.waitForExistence(timeout: 10), "Chitanka card must still be visible")
        XCTAssertTrue(
            app.staticTexts["Coming soon"].exists,
            "Chitanka must be badged so the user is told why it cannot be added"
        )

        card.tap()
        XCTAssertFalse(
            app.staticTexts["Add Chitanka"].waitForExistence(timeout: 5),
            "Tapping Chitanka must not reach the confirmation screen — it cannot be browsed on iOS"
        )
        XCTAssertTrue(app.staticTexts["Add source"].exists, "The picker must still be on screen")
        XCTAssertTrue(app.state == .runningForeground, "Tapping a disabled card must not crash")
    }

    // MARK: - End-to-end add flow

    /// Full add-ABS-source flow: picker → credentials → select-libraries → library home.
    func testAddAbsSourceEndToEnd() throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 120),
                      "App must start on the source picker")

        let absCard = app.staticTexts["Audiobookshelf"]
        XCTAssertTrue(absCard.waitForExistence(timeout: 5), "Picker must show the Audiobookshelf card")
        absCard.tap()

        // Wait for the credential form (scheme selector appears when the form is ready)
        let schemeButton = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH 'https://'"))
            .firstMatch
        XCTAssertTrue(schemeButton.waitForExistence(timeout: 10), "Credential form must show the scheme selector")

        // Type the full URL including "http://"; the ViewModel's updateHost() auto-detects
        // the scheme and strips it into the scheme button — avoids tapping a DropdownMenu
        // which crashes the test runner due to an XCTest/Compose accessibility interaction bug.
        fill(fieldLabeled: "Source URL", with: absServer.baseUrl)
        fill(fieldLabeled: "Username", with: "testuser")
        fill(fieldLabeled: "Password", with: "test")

        let connect = revealConnectButton(in: app)
        XCTAssertTrue(connect.exists, "Connect button must be reachable once fields are filled")
        XCTAssertTrue(connect.isEnabled, "Connect must be enabled once all fields are filled")
        connect.tap()

        if app.buttons["Connect anyway"].waitForExistence(timeout: 10) {
            app.buttons["Connect anyway"].tap()
        }

        let selectLibraries = app.staticTexts["Select libraries"]
        if !selectLibraries.waitForExistence(timeout: 30) {
            print("RIFFLE-E2E-HIERARCHY-BEGIN\n\(app.debugDescription)\nRIFFLE-E2E-HIERARCHY-END")
        }
        XCTAssertTrue(selectLibraries.exists, "Successful login must land on the select-libraries step")
        let continueButton = app.buttons["Continue"]
        XCTAssertTrue(continueButton.waitForExistence(timeout: 5))
        if !continueButton.isEnabled {
            let firstSwitch = app.switches.firstMatch
            XCTAssertTrue(firstSwitch.waitForExistence(timeout: 5))
            firstSwitch.tap()
        }
        continueButton.tap()

        // The committed source becomes active: the library home top bar (burger + library title)
        // replaces the onboarding flow.
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(
            burger.waitForExistence(timeout: 60),
            "Library home must render after adding the source"
        )
        XCTAssertFalse(app.staticTexts["Add source"].exists, "The source picker must be gone")

        let sectionLabels = ["In Progress", "Recently Added", "Finished", "Continue Series", "All Books"]
        let sectionVisible = NSPredicate { _, _ in
            sectionLabels.contains { self.app.staticTexts[$0].exists }
        }
        let refreshed = XCTWaiter.wait(
            for: [XCTNSPredicateExpectation(predicate: sectionVisible, object: nil)],
            timeout: 60
        )
        XCTAssertEqual(refreshed, .completed, "Library items must be fetched and section headers rendered")

        burger.tap()
        XCTAssertTrue(
            app.staticTexts["Audiobookshelf"].waitForExistence(timeout: 10),
            "Drawer must list the added Audiobookshelf source"
        )
    }

    // MARK: - Helpers

    private func fill(fieldLabeled label: String, with text: String) {
        let fieldLabel = app.staticTexts[label]
        XCTAssertTrue(fieldLabel.waitForExistence(timeout: 5), "\(label) field must exist")
        fieldLabel.tap()
        let focused = app.textFields.firstMatch.exists
            ? app.textFields.firstMatch
            : app.secureTextFields.firstMatch
        if focused.exists {
            focused.typeText(text)
        } else {
            app.typeText(text)
        }
    }
}

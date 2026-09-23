import XCTest

// End-to-end coverage for the add-Komga-source flow (scenario 17-source-picker, Komga card).
// Uses StubKomgaServer so the test always has a reachable server — no XCTSkip.
final class AddKomgaSourceFlowTests: XCTestCase {

    private var app: XCUIApplication!
    private var komgaServer: StubKomgaServer!

    override func setUpWithError() throws {
        continueAfterFailure = false
        komgaServer = StubKomgaServer()
        komgaServer.start()
        app = XCUIApplication()
        app.launchArguments += ["--RIFFLE_RESET_FOR_TESTS"]
        app.launch()
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
        komgaServer.shutdown()
        komgaServer = nil
    }

    // MARK: - Picker card presence

    /// The Komga card must appear in the source picker.
    func testKomgaCardVisibleAndEnabled() throws {
        // Cold-launch budget. This is the first thing a harness test does on a fresh app,
        // and on CI it competes with the other simulator clone; issue #1066 grew the suite
        // from 29 to 39 tests across the same two clones and pushed the old 40s past the
        // edge for whichever test runs first in its suite. Later tests in the same class
        // reach this in under 10s because the app is warm.
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 120),
                      "App must start on the source picker")
        let komgaCard = app.staticTexts["Komga"]
        XCTAssertTrue(komgaCard.waitForExistence(timeout: 5), "Picker must show a Komga card")
        XCTAssertTrue(komgaCard.isEnabled, "Komga card must be enabled")
    }

    // MARK: - Credential form reachability

    /// Tapping the Komga card must open the credential form, not crash or show an error.
    func testKomgaCredentialFormReachable() throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 120),
                      "App must start on the source picker")
        let komgaCard = app.staticTexts["Komga"]
        XCTAssertTrue(komgaCard.waitForExistence(timeout: 5))
        komgaCard.tap()

        XCTAssertTrue(
            app.staticTexts["Source URL"].waitForExistence(timeout: 10),
            "Credential form must show a Source URL field"
        )
        XCTAssertTrue(
            app.staticTexts["Username"].exists,
            "Credential form must show a Username field"
        )
    }

    // MARK: - End-to-end add flow

    /// Full add-Komga-source flow: picker → credentials → select-libraries → library home.
    func testAddKomgaSourceEndToEnd() throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 120),
                      "App must start on the source picker")

        let komgaCard = app.staticTexts["Komga"]
        XCTAssertTrue(komgaCard.waitForExistence(timeout: 5), "Picker must show the Komga card")
        komgaCard.tap()

        let schemeButton = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH 'https://'"))
            .firstMatch
        XCTAssertTrue(schemeButton.waitForExistence(timeout: 10), "Credential form must show the scheme selector")

        // Type the full URL including "http://"; the ViewModel's updateHost() auto-detects the
        // scheme and moves it into the scheme button — the same path AddAbsSourceFlowTests uses.
        // Driving the DropdownMenu through XCTest is flaky (Compose accessibility interaction bug)
        // and intermittently left the form with an empty field and a disabled Connect button.
        fill(fieldLabeled: "Source URL", with: komgaServer.baseUrl)
        fill(fieldLabeled: "Username", with: "test@test.test")
        fill(fieldLabeled: "Password", with: "test")

        let connect = revealConnectButton(in: app)
        XCTAssertTrue(connect.exists, "Connect button must be reachable once fields are filled")
        XCTAssertTrue(connect.isEnabled, "Connect must be enabled once all fields are filled")
        connect.tap()

        if app.buttons["Connect anyway"].waitForExistence(timeout: 10) {
            app.buttons["Connect anyway"].tap()
        }

        // 60s, not 30s: on CI this step (login → fetch libraries → render) runs on a contended
        // parallel simulator clone. Measured end-to-end cost of this test is ~24s standalone but
        // ~42s with parallel clones on a fast machine, so a 30s budget for the slowest single step
        // leaves no headroom on slower CI hardware — it expired there while every assertion in the
        // flow still held. The waits below already use 60s for the same reason.
        let selectLibraries = app.staticTexts["Select libraries"]
        if !selectLibraries.waitForExistence(timeout: 60) {
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

        let burger = app.buttons["Open menu"]
        XCTAssertTrue(
            burger.waitForExistence(timeout: 120),
            "Library home must render after adding the Komga source"
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

import XCTest

// Base class for harness tests that need a pre-configured ABS source. Mirrors the pattern of
// Android's GoldenTraceHarnessTest: starts the stub server, resets app state via launch arg,
// then drives the add-source UI to connect to the stub — leaving each test with the library
// home already on screen.
class AbsHarnessTestCase: XCTestCase {

    var app: XCUIApplication!
    var absServer: StubAbsServer!

    override func setUpWithError() throws {
        continueAfterFailure = false
        absServer = StubAbsServer()
        absServer.start()

        app = XCUIApplication()
        app.launchArguments += ["--RIFFLE_RESET_FOR_TESTS"]
        app.launch()

        try connectAbsSource(to: absServer.baseUrl)
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
        absServer.shutdown()
        absServer = nil
    }

    // Drives the add-source UI flow to connect to the stub ABS server.
    private func connectAbsSource(to url: String) throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 15),
                      "App must start with the source picker (reset hook must have fired)")
        let absCard = app.staticTexts["Audiobookshelf"]
        XCTAssertTrue(absCard.waitForExistence(timeout: 5), "ABS card must be in the picker")
        absCard.tap()

        let schemeButton = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH 'https://'"))
            .firstMatch
        XCTAssertTrue(schemeButton.waitForExistence(timeout: 10), "Scheme selector must appear")
        schemeButton.tap()
        let httpOption = app.buttons["http://"].exists
            ? app.buttons["http://"]
            : app.staticTexts.matching(NSPredicate(format: "label == 'http://'")).firstMatch
        XCTAssertTrue(httpOption.waitForExistence(timeout: 5), "http:// option must exist")
        httpOption.tap()

        let host = url.replacingOccurrences(of: "http://", with: "")
        fillField(labeled: "Source URL", with: host)
        fillField(labeled: "Username", with: "testuser")
        fillField(labeled: "Password", with: "test")

        let connect = app.buttons["Connect"]
        XCTAssertTrue(connect.waitForExistence(timeout: 5))
        XCTAssertTrue(connect.isEnabled, "Connect must be enabled once fields are filled")
        connect.tap()

        if app.buttons["Connect anyway"].waitForExistence(timeout: 10) {
            app.buttons["Connect anyway"].tap()
        }

        XCTAssertTrue(app.staticTexts["Select libraries"].waitForExistence(timeout: 30),
                      "Successful login must land on select-libraries step")
        let continueButton = app.buttons["Continue"]
        XCTAssertTrue(continueButton.waitForExistence(timeout: 5))
        if !continueButton.isEnabled {
            let firstSwitch = app.switches.firstMatch
            XCTAssertTrue(firstSwitch.waitForExistence(timeout: 5))
            firstSwitch.tap()
        }
        continueButton.tap()

        XCTAssertTrue(app.staticTexts["☰"].waitForExistence(timeout: 60),
                      "Library home must render after adding ABS source")
    }

    func fillField(labeled label: String, with text: String) {
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

// Base class for harness tests that need a pre-configured Komga source.
class KomgaHarnessTestCase: XCTestCase {

    var app: XCUIApplication!
    var komgaServer: StubKomgaServer!

    override func setUpWithError() throws {
        continueAfterFailure = false
        komgaServer = StubKomgaServer()
        komgaServer.start()

        app = XCUIApplication()
        app.launchArguments += ["--RIFFLE_RESET_FOR_TESTS"]
        app.launch()

        try connectKomgaSource(to: komgaServer.baseUrl)
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
        komgaServer.shutdown()
        komgaServer = nil
    }

    private func connectKomgaSource(to url: String) throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 15),
                      "App must start with the source picker")
        let komgaCard = app.staticTexts["Komga"]
        XCTAssertTrue(komgaCard.waitForExistence(timeout: 5), "Komga card must be in the picker")
        komgaCard.tap()

        let schemeButton = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH 'https://'"))
            .firstMatch
        XCTAssertTrue(schemeButton.waitForExistence(timeout: 10), "Scheme selector must appear")
        schemeButton.tap()
        let httpOption = app.buttons["http://"].exists
            ? app.buttons["http://"]
            : app.staticTexts.matching(NSPredicate(format: "label == 'http://'")).firstMatch
        XCTAssertTrue(httpOption.waitForExistence(timeout: 5))
        httpOption.tap()

        let host = url.replacingOccurrences(of: "http://", with: "")
        fillField(labeled: "Source URL", with: host)
        fillField(labeled: "Username", with: "test@test.test")
        fillField(labeled: "Password", with: "test")

        let connect = app.buttons["Connect"]
        XCTAssertTrue(connect.waitForExistence(timeout: 5))
        XCTAssertTrue(connect.isEnabled)
        connect.tap()

        if app.buttons["Connect anyway"].waitForExistence(timeout: 10) {
            app.buttons["Connect anyway"].tap()
        }

        XCTAssertTrue(app.staticTexts["Select libraries"].waitForExistence(timeout: 30),
                      "Successful login must land on select-libraries step")
        let continueButton = app.buttons["Continue"]
        XCTAssertTrue(continueButton.waitForExistence(timeout: 5))
        if !continueButton.isEnabled {
            let firstSwitch = app.switches.firstMatch
            XCTAssertTrue(firstSwitch.waitForExistence(timeout: 5))
            firstSwitch.tap()
        }
        continueButton.tap()

        XCTAssertTrue(app.staticTexts["☰"].waitForExistence(timeout: 60),
                      "Library home must render after adding Komga source")
    }

    func fillField(labeled label: String, with text: String) {
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

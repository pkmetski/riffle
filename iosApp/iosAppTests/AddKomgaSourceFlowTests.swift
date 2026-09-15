import XCTest

// End-to-end coverage for the add-Komga-source flow (scenario 17-source-picker, Komga card):
// real picker → credential form → select-libraries → library home. Runs against the Komga
// test server; skips when the server is unreachable or a source is already configured.
final class AddKomgaSourceFlowTests: XCTestCase {

    private static let serverUrl =
        ProcessInfo.processInfo.environment["RIFFLE_TEST_KOMGA_URL"] ?? "http://media-server:25600"
    private static let username =
        ProcessInfo.processInfo.environment["RIFFLE_TEST_KOMGA_USER"] ?? "test@test.test"
    private static let password =
        ProcessInfo.processInfo.environment["RIFFLE_TEST_KOMGA_PASSWORD"] ?? "test"

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
    }

    private static func serverReachable() -> Bool {
        guard let url = URL(string: "\(serverUrl)/api/v1/settings") else { return false }
        var request = URLRequest(url: url)
        request.timeoutInterval = 3
        var reachable = false
        let semaphore = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { _, response, _ in
            // Komga returns 401 (needs auth) for /api/v1/settings — that proves it's up.
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            reachable = status == 401 || status == 200
            semaphore.signal()
        }.resume()
        _ = semaphore.wait(timeout: .now() + 5)
        return reachable
    }

    /// Taps the Compose text field by its floating label, then types into the focused field.
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

    // MARK: - Picker card presence

    /// The Komga card must appear in the source picker now that KomgaSourceAdapter is commonMain.
    func testKomgaCardVisibleAndEnabled() throws {
        guard app.staticTexts["Add source"].waitForExistence(timeout: 10) else {
            throw XCTSkip("A source is already configured — picker test requires a pristine install")
        }
        let komgaCard = app.staticTexts["Komga"]
        XCTAssertTrue(
            komgaCard.waitForExistence(timeout: 5),
            "Picker must show a Komga card"
        )
        // The card must be tappable (not greyed-out / disabled).
        XCTAssertTrue(komgaCard.isEnabled, "Komga card must be enabled")
    }

    // MARK: - Credential form reachability

    /// Tapping the Komga card must open the credential form, not crash or show an error.
    func testKomgaCredentialFormReachable() throws {
        guard app.staticTexts["Add source"].waitForExistence(timeout: 10) else {
            throw XCTSkip("A source is already configured — picker test requires a pristine install")
        }
        let komgaCard = app.staticTexts["Komga"]
        XCTAssertTrue(komgaCard.waitForExistence(timeout: 5), "Komga card must exist")
        komgaCard.tap()

        // The credential form must appear with URL + Username + Password fields.
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
        guard Self.serverReachable() else {
            throw XCTSkip("Komga test server \(Self.serverUrl) unreachable — e2e add flow needs a live server")
        }
        guard app.staticTexts["Add source"].waitForExistence(timeout: 10) else {
            throw XCTSkip("A source is already configured — e2e add flow requires a pristine install")
        }

        let komgaCard = app.staticTexts["Komga"]
        XCTAssertTrue(komgaCard.waitForExistence(timeout: 5), "Picker must show the Komga card")
        komgaCard.tap()

        // Credential form: fill URL + username + password.
        let schemeButton = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH 'https://'"))
            .firstMatch
        XCTAssertTrue(schemeButton.waitForExistence(timeout: 10), "Credential form must show the scheme selector")
        if Self.serverUrl.lowercased().hasPrefix("http://") {
            schemeButton.tap()
            let httpOption = app.buttons["http://"].exists
                ? app.buttons["http://"]
                : app.staticTexts.matching(NSPredicate(format: "label == 'http://'")).element(boundBy: 0)
            XCTAssertTrue(httpOption.waitForExistence(timeout: 5), "Scheme dropdown must offer http://")
            httpOption.tap()
        }

        let hostWithoutScheme = Self.serverUrl
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
        fill(fieldLabeled: "Source URL", with: hostWithoutScheme)
        fill(fieldLabeled: "Username", with: Self.username)
        fill(fieldLabeled: "Password", with: Self.password)

        let connect = app.buttons["Connect"]
        XCTAssertTrue(connect.exists, "Connect button must exist")
        XCTAssertTrue(connect.isEnabled, "Connect must be enabled once all fields are filled")
        connect.tap()

        // Plain-HTTP servers trigger the insecure-connection warning.
        let connectAnyway = app.buttons["Connect anyway"]
        if connectAnyway.waitForExistence(timeout: 10) {
            connectAnyway.tap()
        }

        // Select-libraries step.
        let selectLibraries = app.staticTexts["Select libraries"]
        if !selectLibraries.waitForExistence(timeout: 30) {
            print("RIFFLE-E2E-HIERARCHY-BEGIN\n\(app.debugDescription)\nRIFFLE-E2E-HIERARCHY-END")
        }
        XCTAssertTrue(selectLibraries.exists, "Successful login must land on the select-libraries step")
        let continueButton = app.buttons["Continue"]
        XCTAssertTrue(continueButton.waitForExistence(timeout: 5), "Continue button must exist")
        if !continueButton.isEnabled {
            let firstSwitch = app.switches.firstMatch
            XCTAssertTrue(firstSwitch.waitForExistence(timeout: 5), "At least one library row must be listed")
            firstSwitch.tap()
        }
        continueButton.tap()

        let burger = app.staticTexts["☰"]
        XCTAssertTrue(
            burger.waitForExistence(timeout: 60),
            "Library home must render after adding the Komga source"
        )
    }
}

import XCTest

// End-to-end coverage for the add-Audiobookshelf-source flow (scenario 17-source-picker):
// real picker → credential form → select-libraries → library home. Runs against the ABS
// test server; skips when the server is unreachable or a source is already configured
// (the flow needs a pristine install).
final class AddAbsSourceFlowTests: XCTestCase {

    private static let serverUrl =
        ProcessInfo.processInfo.environment["RIFFLE_TEST_ABS_URL"] ?? "http://media-server:13378"
    private static let username =
        ProcessInfo.processInfo.environment["RIFFLE_TEST_ABS_USER"] ?? "test2"
    private static let password =
        ProcessInfo.processInfo.environment["RIFFLE_TEST_ABS_PASSWORD"] ?? "test"

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
        guard let url = URL(string: "\(serverUrl)/status") else { return false }
        var request = URLRequest(url: url)
        request.timeoutInterval = 3
        var reachable = false
        let semaphore = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { _, response, _ in
            reachable = (response as? HTTPURLResponse)?.statusCode == 200
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
        // Once focused, the field becomes a first-class text field / secure field.
        let focused = app.textFields.firstMatch.exists
            ? app.textFields.firstMatch
            : app.secureTextFields.firstMatch
        if focused.exists {
            focused.typeText(text)
        } else {
            app.typeText(text)
        }
    }

    func testAddAbsSourceEndToEnd() throws {
        guard Self.serverReachable() else {
            throw XCTSkip("ABS test server \(Self.serverUrl) unreachable — e2e add flow needs a live server")
        }
        guard app.staticTexts["Add source"].waitForExistence(timeout: 10) else {
            throw XCTSkip("A source is already configured — e2e add flow requires a pristine install")
        }

        // Picker: the Audiobookshelf card must be present and tappable.
        let absCard = app.staticTexts["Audiobookshelf"]
        XCTAssertTrue(absCard.waitForExistence(timeout: 5), "Picker must show the Audiobookshelf card")
        absCard.tap()

        // Credential form. Compose exposes the scheme selector as a button whose label combines
        // the current value and content description, and text fields only gain the text-field
        // trait when focused — so tap the field's label and type into the focused element.
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

        // Plain-HTTP servers trigger the insecure-connection warning (same as Android).
        let connectAnyway = app.buttons["Connect anyway"]
        if connectAnyway.waitForExistence(timeout: 10) {
            connectAnyway.tap()
        }

        // Select-libraries step: the server's book libraries are listed.
        let selectLibraries = app.staticTexts["Select libraries"]
        if !selectLibraries.waitForExistence(timeout: 30) {
            print("RIFFLE-E2E-HIERARCHY-BEGIN\n\(app.debugDescription)\nRIFFLE-E2E-HIERARCHY-END")
        }
        XCTAssertTrue(selectLibraries.exists, "Successful login must land on the select-libraries step")
        let continueButton = app.buttons["Continue"]
        XCTAssertTrue(continueButton.waitForExistence(timeout: 5), "Continue button must exist")
        if !continueButton.isEnabled {
            // No library preselected — enable the first one.
            let firstSwitch = app.switches.firstMatch
            XCTAssertTrue(firstSwitch.waitForExistence(timeout: 5), "At least one library row must be listed")
            firstSwitch.tap()
        }
        continueButton.tap()

        // The committed source becomes active: the library home top bar (burger + library title)
        // replaces the onboarding flow.
        let burger = app.staticTexts["☰"]
        XCTAssertTrue(
            burger.waitForExistence(timeout: 60),
            "Library home must render after adding the source",
        )
        XCTAssertFalse(app.staticTexts["Add source"].exists, "The source picker must be gone")

        // Library items must actually be fetched from the server: at least one section header
        // renders once the post-add refresh completes. (Regression: the iOS shell never
        // triggered refreshLibraryItems, so a freshly added source stayed empty forever.)
        let sectionLabels = ["In Progress", "Recently Added", "Finished", "Continue Series", "All Books"]
        let sectionVisible = NSPredicate { _, _ in
            sectionLabels.contains { self.app.staticTexts[$0].exists }
        }
        let refreshed = XCTWaiter.wait(
            for: [XCTNSPredicateExpectation(predicate: sectionVisible, object: nil)],
            timeout: 60,
        )
        XCTAssertEqual(refreshed, .completed, "Library items must be fetched and section headers rendered")

        // The drawer lists the newly added Audiobookshelf source.
        burger.tap()
        XCTAssertTrue(
            app.staticTexts["Audiobookshelf"].waitForExistence(timeout: 10),
            "Drawer must list the added Audiobookshelf source",
        )
    }
}

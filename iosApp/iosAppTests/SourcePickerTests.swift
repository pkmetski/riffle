import XCTest

// Server-free coverage for the Add-Source picker (scenario 17-source-picker).
// Every test here runs in CI without any backend server.  Server-backed e2e flows live in
// AddAbsSourceFlowTests (ABS) and AddKomgaSourceFlowTests (Komga).
final class SourcePickerTests: XCTestCase {

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

    // MARK: - 17.1  All picker cards visible

    /// The picker must show all six source cards that iOS supports on a pristine install.
    func testAllSourceCardsVisibleOnPristineInstall() throws {
        guard app.staticTexts["Add source"].waitForExistence(timeout: 10) else {
            throw XCTSkip("A source is already configured — picker card visibility test requires a pristine install")
        }
        XCTAssertTrue(app.staticTexts["Audiobookshelf"].waitForExistence(timeout: 5), "ABS card must be visible")
        XCTAssertTrue(app.staticTexts["Local files"].exists, "Local files card must be visible")
        XCTAssertTrue(app.staticTexts["Chitanka"].exists, "Chitanka card must be visible")
        XCTAssertTrue(app.staticTexts["Project Gutenberg"].exists, "Project Gutenberg card must be visible")
        XCTAssertTrue(app.staticTexts["Komga"].exists, "Komga card must be visible")
        XCTAssertTrue(app.staticTexts["radio.es"].exists, "radio.es card must be visible")
    }

    // MARK: - 17.2  ABS credential form reachable (no server)

    /// Tapping the Audiobookshelf card must open the credential form without needing a live server.
    func testAbsCredentialFormReachable() throws {
        guard app.staticTexts["Add source"].waitForExistence(timeout: 10) else {
            throw XCTSkip("A source is already configured — credential form test requires a pristine install")
        }
        let absCard = app.staticTexts["Audiobookshelf"]
        XCTAssertTrue(absCard.waitForExistence(timeout: 5), "Audiobookshelf card must exist")
        absCard.tap()

        // The credential form must appear with URL + Username + Password fields and a scheme
        // selector. No server connection is made until Connect is tapped.
        let schemeButton = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH 'https://'"))
            .firstMatch
        XCTAssertTrue(
            schemeButton.waitForExistence(timeout: 10),
            "Credential form must show the scheme selector"
        )
        XCTAssertTrue(app.staticTexts["Source URL"].exists, "Credential form must show a Source URL field")
        XCTAssertTrue(app.staticTexts["Username"].exists, "Credential form must show a Username field")

        // Connect must be disabled until the user fills in the required fields — tapping an
        // empty form must not send any network request.
        let connect = app.buttons["Connect"]
        XCTAssertTrue(connect.waitForExistence(timeout: 5), "Connect button must exist")
        XCTAssertFalse(connect.isEnabled, "Connect must be disabled when the URL field is empty")
    }

    // MARK: - 17.3  Project Gutenberg install (zero-config, no server)

    /// Tapping the Project Gutenberg card must show the confirmation screen first (B3 fix).
    /// The user then taps "Add source" and lands on the library home.
    func testGutenbergInstallDoesNotCrash() throws {
        guard app.staticTexts["Add source"].waitForExistence(timeout: 10) else {
            throw XCTSkip("A source is already configured — install test requires a pristine install")
        }
        let gutenbergCard = app.staticTexts["Project Gutenberg"]
        XCTAssertTrue(gutenbergCard.waitForExistence(timeout: 5), "Project Gutenberg card must be visible")

        let hittable = NSPredicate(format: "hittable == true")
        wait(for: [XCTNSPredicateExpectation(predicate: hittable, object: gutenbergCard)], timeout: 10)
        gutenbergCard.tap()

        // B3: a confirmation screen must appear — not an immediate install.
        // The top bar title says "Add Project Gutenberg".
        let confirmTitle = app.staticTexts["Add Project Gutenberg"]
        XCTAssertTrue(
            confirmTitle.waitForExistence(timeout: 10),
            "Tapping Gutenberg card must show the confirmation screen, not install immediately"
        )

        // Tapping "Add source" confirms the install and lands on library home.
        let addButton = app.buttons["Add source"]
        XCTAssertTrue(addButton.waitForExistence(timeout: 5), "Confirmation screen must have an Add source button")
        addButton.tap()

        let burger = app.buttons["Open menu"]
        XCTAssertTrue(
            burger.waitForExistence(timeout: 30),
            "Project Gutenberg install must land on the library home"
        )
        XCTAssertTrue(app.state == .runningForeground, "App must survive Project Gutenberg install")

        // Clean up so subsequent tests see a pristine no-source state.
        burger.tap()
        let settingsEntry = app.staticTexts["Settings"]
        XCTAssertTrue(settingsEntry.waitForExistence(timeout: 10), "Drawer must offer Settings")
        settingsEntry.tap()
        let removeButton = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'settings-trailing-Remove'")).firstMatch
        XCTAssertTrue(
            removeButton.waitForExistence(timeout: 15),
            "Settings must list the source with a Remove action"
        )
        removeButton.tap()
        XCTAssertTrue(
            app.staticTexts["No sources configured"].waitForExistence(timeout: 10),
            "Removing the only source must leave Settings empty"
        )
    }

    // MARK: - 17.6  radio.es install (zero-config, no server)

    /// Tapping the radio.es card must show the confirmation screen first (B3 fix).
    /// The user then taps "Add source" and lands on the library home.
    func testRadioEsInstallDoesNotCrash() throws {
        guard app.staticTexts["Add source"].waitForExistence(timeout: 10) else {
            throw XCTSkip("A source is already configured — install test requires a pristine install")
        }
        let radioEsCard = app.staticTexts["radio.es"]
        XCTAssertTrue(radioEsCard.waitForExistence(timeout: 5), "radio.es card must be visible")

        let hittable = NSPredicate(format: "hittable == true")
        wait(for: [XCTNSPredicateExpectation(predicate: hittable, object: radioEsCard)], timeout: 10)
        radioEsCard.tap()

        // B3: a confirmation screen must appear before the install completes.
        let confirmTitle = app.staticTexts["Add radio.es"]
        XCTAssertTrue(
            confirmTitle.waitForExistence(timeout: 10),
            "Tapping radio.es card must show the confirmation screen, not install immediately"
        )

        let addButton = app.buttons["Add source"]
        XCTAssertTrue(addButton.waitForExistence(timeout: 5), "Confirmation screen must have an Add source button")
        addButton.tap()

        let burger = app.buttons["Open menu"]
        XCTAssertTrue(
            burger.waitForExistence(timeout: 30),
            "radio.es install must land on the library home"
        )
        XCTAssertTrue(app.state == .runningForeground, "App must survive radio.es install")

        burger.tap()
        let settingsEntry = app.staticTexts["Settings"]
        XCTAssertTrue(settingsEntry.waitForExistence(timeout: 10), "Drawer must offer Settings")
        settingsEntry.tap()
        let removeButton = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'settings-trailing-Remove'")).firstMatch
        XCTAssertTrue(
            removeButton.waitForExistence(timeout: 15),
            "Settings must list the source with a Remove action"
        )
        removeButton.tap()
        XCTAssertTrue(
            app.staticTexts["No sources configured"].waitForExistence(timeout: 10),
            "Removing the only source must leave Settings empty"
        )
    }
}

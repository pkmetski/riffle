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

    // MARK: - Chitanka regression

    /// Regression for the 2026-09-07 Chitanka-install crash (IosLogger routed messages through
    /// NSLog varargs, which segfaults on Kotlin/Native): installing a zero-config catalog source
    /// must complete with the app alive.
    func testChitankaInstallDoesNotCrash() throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 10),
                      "App must start on the source picker")
        app.staticTexts["Chitanka"].tap()
        // B3: tapping Chitanka now navigates to a confirmation screen before installing.
        let confirmTitle = app.staticTexts["Add Chitanka"]
        XCTAssertTrue(confirmTitle.waitForExistence(timeout: 10), "Chitanka picker tap must show confirmation screen")
        let addButton = app.buttons["Add source"]
        XCTAssertTrue(addButton.waitForExistence(timeout: 5), "Confirmation screen must have an Add source button")
        addButton.tap()
        // Install writes the source + libraries and redirects to the library home.
        let burger = app.buttons["Open menu"]
        XCTAssertTrue(burger.waitForExistence(timeout: 30), "Chitanka install must land on the library home")
        XCTAssertTrue(app.state == .runningForeground, "App must survive Chitanka install")

        burger.tap()
        let settingsEntry = app.staticTexts["Settings"]
        XCTAssertTrue(settingsEntry.waitForExistence(timeout: 10), "Drawer must offer Settings")
        settingsEntry.tap()
        // CMP sets Modifier.testTag("settings-trailing-Remove") on the trailing action button,
        // which maps to accessibilityIdentifier on iOS — query by identifier for robustness.
        let removeButton = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'settings-trailing-Remove'")).firstMatch
        let removeFound = removeButton.waitForExistence(timeout: 15)
        if !removeFound {
            let allElements = app.descendants(matching: .any).allElementsBoundByIndex
            print("=== Settings screen elements (\(allElements.count)) ===")
            for (idx, element) in allElements.prefix(60).enumerated() {
                print("[\(idx)] type=\(element.elementType.rawValue) id='\(element.identifier)' label='\(element.label)'")
            }
        }
        XCTAssertTrue(removeFound, "Settings must list the source with a Remove action")
        removeButton.tap()
        XCTAssertTrue(
            app.staticTexts["No sources configured"].waitForExistence(timeout: 10),
            "Removing the only source must leave Settings empty"
        )
    }

    // MARK: - End-to-end add flow

    /// Full add-ABS-source flow: picker → credentials → select-libraries → library home.
    func testAddAbsSourceEndToEnd() throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 10),
                      "App must start on the source picker")

        let absCard = app.staticTexts["Audiobookshelf"]
        XCTAssertTrue(absCard.waitForExistence(timeout: 5), "Picker must show the Audiobookshelf card")
        absCard.tap()

        let schemeButton = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH 'https://'"))
            .firstMatch
        XCTAssertTrue(schemeButton.waitForExistence(timeout: 10), "Credential form must show the scheme selector")
        schemeButton.tap()
        let httpOption = app.buttons["http://"].exists
            ? app.buttons["http://"]
            : app.staticTexts.matching(NSPredicate(format: "label == 'http://'")).firstMatch
        XCTAssertTrue(httpOption.waitForExistence(timeout: 5), "Scheme dropdown must offer http://")
        httpOption.tap()

        let host = absServer.baseUrl.replacingOccurrences(of: "http://", with: "")
        fill(fieldLabeled: "Source URL", with: host)
        fill(fieldLabeled: "Username", with: "testuser")
        fill(fieldLabeled: "Password", with: "test")

        let connect = app.buttons["Connect"]
        XCTAssertTrue(connect.waitForExistence(timeout: 5))
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

import XCTest

final class LocalFilesTests: XCTestCase {

    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    // MARK: - Scenario B: Cancel the picker

    func testCancelFolderPickerReturnsToAddSourceButton() throws {
        let addButton = app.buttons["Add Local Files"]
        XCTAssertTrue(
            addButton.waitForExistence(timeout: 5),
            "Add Local Files button should be visible on first launch with no sources"
        )

        addButton.tap()

        // The iOS document picker appears with a Cancel button.
        let cancelButton = app.buttons["Cancel"]
        XCTAssertTrue(cancelButton.waitForExistence(timeout: 5), "Picker Cancel button should appear")
        cancelButton.tap()

        // After cancellation the Add Local Files button should be visible again.
        XCTAssertTrue(
            addButton.waitForExistence(timeout: 5),
            "Add Local Files button should reappear after cancel"
        )
    }

    // Scenario A (pick a real folder) requires a pre-seeded simulator and cannot be driven
    // reliably by XCUITest without additional infrastructure. Verified manually per the scenario doc.
    // See docs/testing/ios-scenarios/01-local-files.md — Scenario A.
}

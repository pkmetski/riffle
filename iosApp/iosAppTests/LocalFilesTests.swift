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
        // First launch with no sources lands on the shared source-type picker.
        guard app.staticTexts["Add source"].waitForExistence(timeout: 10) else {
            throw XCTSkip("A source is already configured — cancel-flow test requires a pristine install")
        }
        let localFilesCard = app.staticTexts["Local files"]
        XCTAssertTrue(
            localFilesCard.waitForExistence(timeout: 5),
            "Local files card should be visible in the source picker"
        )

        localFilesCard.tap()

        // The iOS document picker appears with a Cancel button.
        let cancelButton = app.buttons["Cancel"]
        XCTAssertTrue(cancelButton.waitForExistence(timeout: 5), "Picker Cancel button should appear")
        cancelButton.tap()

        // After cancellation the source picker is visible again.
        XCTAssertTrue(
            localFilesCard.waitForExistence(timeout: 5),
            "Local files card should reappear after cancel"
        )
    }

    // Scenario A (pick a real folder) requires a pre-seeded simulator and cannot be driven
    // reliably by XCUITest without additional infrastructure. Verified manually per the scenario doc.
    // See docs/testing/ios-scenarios/01-local-files.md — Scenario A.
}

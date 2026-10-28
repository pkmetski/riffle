import XCTest

// Covers the local-files source scenarios. Requires a pristine install — guaranteed by
// --RIFFLE_RESET_FOR_TESTS launch arg; no XCTSkip.
final class LocalFilesTests: XCTestCase {

    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments += ["--RIFFLE_RESET_FOR_TESTS"]
        app.launch()
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
    }

    // MARK: - Scenario B: Cancel the picker

    // After tapping "Local files" the shared AddLocalFilesScreen auto-opens the system folder
    // picker. Cancelling the system picker leaves the user on the AddLocalFilesScreen's Cancelled
    // state ("No folder picked"), from which a second Cancel tap returns to the source picker.
    func testCancelFolderPickerReturnsToAddSourceButton() throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 10),
                      "App must start on the source picker")
        let localFilesCard = app.staticTexts["Local files"]
        XCTAssertTrue(
            localFilesCard.waitForExistence(timeout: 5),
            "Local files card should be visible in the source picker"
        )
        let hittable = NSPredicate(format: "hittable == true")
        let hittableExp = XCTNSPredicateExpectation(predicate: hittable, object: localFilesCard)
        wait(for: [hittableExp], timeout: 10)

        localFilesCard.tap()

        // Step 1: Cancel the system UIDocumentPickerViewController.
        let systemCancelButton = app.buttons["Cancel"]
        XCTAssertTrue(systemCancelButton.waitForExistence(timeout: 30),
                      "System folder picker Cancel button should appear")
        systemCancelButton.tap()

        // Step 2: AddLocalFilesScreen now shows the Cancelled state ("No folder picked").
        // Tap Cancel to return to the source picker.
        XCTAssertTrue(
            app.staticTexts["No folder picked."].waitForExistence(timeout: 10),
            "AddLocalFilesScreen must show 'No folder picked.' after picker cancel"
        )
        let screenCancelButton = app.buttons["Cancel"].firstMatch
        XCTAssertTrue(screenCancelButton.waitForExistence(timeout: 5),
                      "AddLocalFilesScreen must offer a Cancel button in the Cancelled state")
        screenCancelButton.tap()

        XCTAssertTrue(
            localFilesCard.waitForExistence(timeout: 10),
            "Local files card should reappear after dismissing the AddLocalFilesScreen"
        )
    }
}

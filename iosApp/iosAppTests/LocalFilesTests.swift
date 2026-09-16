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

        let cancelButton = app.buttons["Cancel"]
        XCTAssertTrue(cancelButton.waitForExistence(timeout: 30), "Picker Cancel button should appear")
        cancelButton.tap()

        XCTAssertTrue(
            localFilesCard.waitForExistence(timeout: 5),
            "Local files card should reappear after cancel"
        )
    }
}

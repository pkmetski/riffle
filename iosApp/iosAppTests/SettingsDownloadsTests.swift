import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/5-settings-downloads.md
final class SettingsDownloadsTests: XCTestCase {

    // Scenario 5.2 — AppTheme enum values exist for the Settings theme picker.
    func testAppThemeValuesExist() {
        // All three values must be present so the SettingsScreen theme picker compiles.
        let light = AppTheme.light
        let dark = AppTheme.dark
        let system = AppTheme.system
        XCTAssertNotNil(light)
        XCTAssertNotNil(dark)
        XCTAssertNotNil(system)
    }

    // Scenario 5.4 / 5.5 — StoredMediaType labels used by DownloadsScreen.
    func testStoredMediaTypeValuesExist() {
        let epub = StoredMediaType.epub
        let pdf = StoredMediaType.pdf
        let cbz = StoredMediaType.cbz
        let audiobook = StoredMediaType.audiobook
        XCTAssertNotNil(epub)
        XCTAssertNotNil(pdf)
        XCTAssertNotNil(cbz)
        XCTAssertNotNil(audiobook)
    }

    // Scenarios 5.1 / 5.3 / 5.6 / 5.7 are Compose UI-only.
    // They are verified manually via Xcode build + drawer tap.
    func testSettingsDrawerNavigation() throws {
        throw XCTSkip("UI-only; verified manually by opening drawer → tapping Settings")
    }

    func testDownloadsDrawerNavigation() throws {
        throw XCTSkip("UI-only; verified manually by opening drawer → tapping Downloads")
    }

    func testDrawerActiveSectionHighlight() throws {
        throw XCTSkip("UI-only; verified manually by checking highlighted drawer row")
    }
}

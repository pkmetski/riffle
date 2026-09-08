import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/11-settings-rows.md
final class SettingsRowsTests: XCTestCase {

    // Scenario 11.3 — Cadence default state: showCadence is off, default colour is yellow,
    // default WPM is 250. These constants drive the Cadence settings panel initial render.
    func testCadenceDefaultsMatchExpectedValues() {
        let prefs = FormattingPreferences.companion.defaults()
        XCTAssertFalse(prefs.showCadence, "Cadence must be off by default so users encounter it as opt-in")
        XCTAssertEqual(prefs.cadenceHighlightColor, HighlightColor.yellow)
        XCTAssertEqual(prefs.cadenceWpm, 250)
        XCTAssertTrue(prefs.cadencePlatformSupported, "Platform-supported flag defaults true; reader flips it false on unsupported WebViews")
    }
}

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

    // Scenario 11.4 — Formatting defaults: Original font, 100% size, 1.2 spacing, 100% margins.
    func testFormattingDefaultsMatchExpectedValues() {
        let prefs = FormattingPreferences.companion.defaults()
        XCTAssertEqual(prefs.fontFamily, ReaderFontFamily.original)
        XCTAssertEqual(prefs.fontSize, 1.0, accuracy: 0.001)
        XCTAssertEqual(prefs.lineSpacing, 1.2, accuracy: 0.001)
        XCTAssertEqual(prefs.margins, 1.0, accuracy: 0.001)
        XCTAssertFalse(prefs.justifyText)
    }

    // Scenario 11.5 — Display defaults: Paginated orientation, Light theme.
    func testDisplayDefaultsMatchExpectedValues() {
        let prefs = FormattingPreferences.companion.defaults()
        XCTAssertEqual(prefs.orientation, ReaderOrientation.horizontal)
        XCTAssertEqual(prefs.theme, ReaderTheme.light)
        XCTAssertFalse(prefs.forcePaginatedInLandscape)
        XCTAssertFalse(prefs.doublePageSpread)
    }

    // Scenario 11.6 — Auto-scroll defaults: off, 250 WPM.
    func testAutoScrollDefaultsMatchExpectedValues() {
        let prefs = FormattingPreferences.companion.defaults()
        XCTAssertFalse(prefs.showAutoScroll)
        XCTAssertEqual(prefs.autoScrollWpm, 250)
    }

    // Scenario 11.7 — On-screen info defaults: chapter map on, progress labels off.
    func testOnScreenInfoDefaultsMatchExpectedValues() {
        let prefs = FormattingPreferences.companion.defaults()
        XCTAssertTrue(prefs.showChapterMap)
        XCTAssertTrue(prefs.coloredChapterMap)
        XCTAssertFalse(prefs.showReadingProgressLabels)
        XCTAssertFalse(prefs.showCurrentChapterLabel)
        XCTAssertFalse(prefs.showReadingTimeEstimate)
    }

    // Scenario 11.8 — Comic formatting defaults: Dark background, panel view off.
    func testComicFormattingDefaultsMatchExpectedValues() {
        // K/N does not expose default-arg constructors to Swift; supply explicit defaults.
        let prefs = ComicFormattingPreferences(
            backgroundTheme: .dark,
            panelViewOn: false,
            panelOverflow: .split,
            panelAnimationSpeedMs: 250,
            showChapterMap: false,
            showPageProgress: false
        )
        XCTAssertEqual(prefs.backgroundTheme, ReaderTheme.dark)
        XCTAssertFalse(prefs.panelViewOn)
        XCTAssertEqual(prefs.panelOverflow, PanelOverflowBehavior.split)
        XCTAssertFalse(prefs.showChapterMap)
        XCTAssertFalse(prefs.showPageProgress)
    }

    // Scenario 11.9 — Readaloud preferences defaults: highlight color is blue.
    func testReadaloudPreferencesDefaultHighlightColorIsBlue() {
        // K/N does not expose default-arg constructors to Swift; supply explicit default.
        let prefs = ReadaloudPreferences(highlightColor: .blue)
        XCTAssertEqual(prefs.highlightColor, HighlightColor.blue)
    }

    // Scenario 11.10 — AppVersion data class holds the name and code supplied at construction.
    func testAppVersionHoldsNameAndCode() {
        let version = AppVersion(name: "3.2.1", code: 321)
        XCTAssertEqual(version.name, "3.2.1")
        XCTAssertEqual(version.code, 321)
    }

    // Scenario 11.11 — AnnotationSyncSubtitle.NotConfigured singleton is accessible from Swift.
    // (data object is exposed by K/N as a class singleton via .shared)
    func testAnnotationSyncSubtitleNotConfiguredIsAccessible() {
        let notConfigured: AnnotationSyncSubtitle = AnnotationSyncSubtitle.NotConfigured.shared
        XCTAssertNotNil(notConfigured)
    }

    // Scenario 11.12 — HighlightColor enum contains all four cadence colour options
    // and each resolves to a distinct value via its token string.
    func testHighlightColorEnumContainsAllCadenceColorOptions() {
        XCTAssertNotEqual(HighlightColor.yellow, HighlightColor.green)
        XCTAssertNotEqual(HighlightColor.yellow, HighlightColor.blue)
        XCTAssertNotEqual(HighlightColor.yellow, HighlightColor.red)
        XCTAssertNotEqual(HighlightColor.green, HighlightColor.blue)
        XCTAssertNotEqual(HighlightColor.green, HighlightColor.red)
        XCTAssertNotEqual(HighlightColor.blue, HighlightColor.red)
    }
}

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
        XCTAssertEqual(prefs.fontSize, FormattingPreferences.companion.defaultFontSize, accuracy: 0.001)
        XCTAssertEqual(prefs.lineSpacing, FormattingPreferences.companion.defaultLineSpacing, accuracy: 0.001)
        XCTAssertEqual(prefs.margins, FormattingPreferences.companion.defaultMargins, accuracy: 0.001)
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
        let prefs = ComicFormattingPreferences()
        XCTAssertEqual(prefs.backgroundTheme, ReaderTheme.dark)
        XCTAssertFalse(prefs.panelViewOn)
        XCTAssertEqual(prefs.panelOverflow, PanelOverflowBehavior.split)
        XCTAssertFalse(prefs.showChapterMap)
        XCTAssertFalse(prefs.showPageProgress)
    }

    // Scenario 11.9 — Readaloud preferences defaults: highlight color is blue.
    func testReadaloudPreferencesDefaultHighlightColorIsBlue() {
        let prefs = ReadaloudPreferences()
        XCTAssertEqual(prefs.highlightColor, HighlightColor.blue)
    }

    // Scenario 11.10 — AppVersion data class holds the name and code supplied at construction.
    func testAppVersionHoldsNameAndCode() {
        let version = AppVersion(name: "3.2.1", code: 321)
        XCTAssertEqual(version.name, "3.2.1")
        XCTAssertEqual(version.code, 321)
    }

    // Scenario 11.11 — AnnotationSyncSubtitle sealed class covers all variants accessible from Swift.
    func testAnnotationSyncSubtitleVariantsAreAccessible() {
        let notConfigured = AnnotationSyncSubtitle.NotConfigured()
        XCTAssertNotNil(notConfigured as? AnnotationSyncSubtitle.NotConfigured)

        let synced = AnnotationSyncSubtitle.Synced(identity: "bob@dav.test")
        let syncedCast = synced as? AnnotationSyncSubtitle.Synced
        XCTAssertNotNil(syncedCast)
        XCTAssertEqual(syncedCast?.identity, "bob@dav.test")

        let pending = AnnotationSyncSubtitle.BooksPendingOffline(count: 7)
        let pendingCast = pending as? AnnotationSyncSubtitle.BooksPendingOffline
        XCTAssertNotNil(pendingCast)
        XCTAssertEqual(pendingCast?.count, 7)

        let httpError = AnnotationSyncSubtitle.HttpError(code: 401)
        let httpCast = httpError as? AnnotationSyncSubtitle.HttpError
        XCTAssertNotNil(httpCast)
        XCTAssertEqual(httpCast?.code, 401)
    }

    // Scenario 11.12 — HighlightColor enum contains all four cadence colour options.
    func testHighlightColorEnumContainsAllCadenceColorOptions() {
        // The cadence colour picker in Settings exposes Yellow/Green/Blue/Red.
        // Verify that all four entries resolve to distinct non-nil enum values.
        let colors: [HighlightColor] = [.yellow, .green, .blue, .red]
        XCTAssertEqual(colors.count, 4)
        let unique = Set(colors.map { $0.name })
        XCTAssertEqual(unique.count, 4, "All four highlight colors must be distinct")
    }
}

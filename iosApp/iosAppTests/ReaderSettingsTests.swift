import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/12-reader-settings.md
final class ReaderSettingsTests: XCTestCase {

    // Scenario 12.4 — AutoReaderThemeMode default is Schedule (not AppTheme), so the schedule
    // editor is shown by default when Auto is selected. If this changes, the Display section
    // renders incorrectly (shows App-theme sub-row instead of Day-starts-at editor).
    func testAutoReaderThemeModeDefaultIsSchedule() {
        let prefs = FormattingPreferences.companion.defaults()
        XCTAssertEqual(prefs.autoReaderThemeMode, AutoReaderThemeMode.schedule)
    }

    // Scenario 12.6 — Colored chapter map is on by default. If this regresses to false,
    // the chapter rail always renders in neutral grey even when the user hasn't changed the setting.
    func testColoredChapterMapDefaultIsTrue() {
        let prefs = FormattingPreferences.companion.defaults()
        XCTAssertTrue(prefs.coloredChapterMap)
    }

    // Scenario 12.10 — forcePaginatedInLandscape defaults to false (opt-in). If this regresses to
    // true, all users in landscape would be forced into paginated mode without opting in.
    func testForcePaginatedInLandscapeDefaultIsFalse() {
        let prefs = FormattingPreferences.companion.defaults()
        XCTAssertFalse(prefs.forcePaginatedInLandscape)
    }

    // Scenario 12.11 — effectiveOrientation() is callable from Swift and honours the stored
    // orientation when the flag is off. The full override logic (flag=true + landscape → Horizontal)
    // is covered by JVM tests; this verifies the function is wired and accessible via Kotlin/Native.
    func testEffectiveOrientation_flagOff_returnsStoredOrientation() {
        let prefs = FormattingPreferences.companion.defaults()
        // defaults() has orientation=Horizontal, forcePaginatedInLandscape=false
        let result = FormattingPreferencesKt.effectiveOrientation(prefs, isLandscape: true)
        XCTAssertEqual(result, ReaderOrientation.horizontal)
    }
}

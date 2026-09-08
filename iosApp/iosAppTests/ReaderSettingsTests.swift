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
}

import XCTest
import Riffle

/// Reader-settings defaults, capability gating and row summaries on the iOS code path.
///
/// The gating rules (`ReaderSettingsSections`), the capability flags (`RenderCapabilities`) and
/// the un-localised row summaries (`ReaderSettingsSummaries`) used to be inline conditions and
/// top-level functions inside the Android `app` module, so iOS had no way to reach them. They now
/// live in `feature:settings/commonMain` and Android's composables/extension functions forward to
/// them, which makes these assertions the iOS half of:
///
///  - `app/src/test/.../RenderCapabilitiesTest.kt`
///  - `app/src/test/.../ReaderSettingsSummariesTest.kt`
///  - `app/src/androidTest/.../ReaderSettingsSheetCapabilitiesTest.kt`
///  - `app/src/androidTest/.../ReaderSettingsSectionsTest.kt`
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

    // MARK: - Capability gating (RenderCapabilitiesTest)

    /// An EPUB is reflowable, so every formatting control is meaningful.
    func testEpubDeclaresFullCapabilities() {
        let caps = RenderCapabilities.companion.EPUB
        XCTAssertTrue(caps.supportsFontFamily)
        XCTAssertTrue(caps.supportsTextTypography)
        XCTAssertTrue(caps.supportsPublisherStyles)
        XCTAssertTrue(caps.supportsTheme)
        XCTAssertTrue(caps.supportsReadingModeSwitch)
        XCTAssertTrue(caps.supportsDoublePage)
        XCTAssertTrue(caps.supportsPositionOverlays)
    }

    /// A PDF is a fixed page image: none of the reflow-dependent controls do anything, so they
    /// must not render as dead switches. If any of these flipped true the PDF settings sheet
    /// would grow controls that silently do nothing.
    func testPdfDisablesEveryReflowDependentCapability() {
        let caps = RenderCapabilities.companion.PDF
        XCTAssertFalse(caps.supportsFontFamily)
        XCTAssertFalse(caps.supportsTextTypography)
        XCTAssertFalse(caps.supportsPublisherStyles)
        XCTAssertFalse(caps.supportsTheme)
        XCTAssertFalse(caps.supportsReadingModeSwitch)
        XCTAssertFalse(caps.supportsDoublePage)
        XCTAssertFalse(caps.supportsPositionOverlays)
    }

    // MARK: - Section visibility (ReaderSettingsSheetCapabilitiesTest / ReaderSettingsSectionsTest)

    /// The "View" header must not be left stranded above nothing: a PDF supports neither the
    /// reading-mode switch nor double-page, so the header itself is suppressed.
    func testViewSectionHeaderIsHiddenWhenNoViewControlRenders() {
        let sections = ReaderSettingsSections.shared
        XCTAssertTrue(sections.showsViewSectionHeader(capabilities: RenderCapabilities.companion.EPUB))
        XCTAssertFalse(sections.showsViewSectionHeader(capabilities: RenderCapabilities.companion.PDF))
    }

    /// Double-page only has a rendering effect when paginated is the active mode, or when
    /// landscape will promote to paginated — otherwise the toggle stays visible but inert.
    func testDoublePageToggleIsEnabledOnlyWhenPaginationCanApply() {
        let sections = ReaderSettingsSections.shared
        XCTAssertTrue(sections.doublePageToggleEnabled(orientation: .horizontal, forcePaginatedInLandscape: false))
        XCTAssertFalse(sections.doublePageToggleEnabled(orientation: .vertical, forcePaginatedInLandscape: false))
        XCTAssertFalse(sections.doublePageToggleEnabled(orientation: .continuous, forcePaginatedInLandscape: false))
        XCTAssertTrue(
            sections.doublePageToggleEnabled(orientation: .vertical, forcePaginatedInLandscape: true),
            "forcePaginatedInLandscape re-arms the toggle even from a scrolling base mode"
        )
    }

    /// The Auto sub-controls belong to the Auto theme only; any concrete theme hides them.
    func testAutoThemeBlockAppearsOnlyForTheAutoTheme() {
        let sections = ReaderSettingsSections.shared
        XCTAssertTrue(sections.showsAutoThemeBlock(theme: .auto_))
        XCTAssertFalse(sections.showsAutoThemeBlock(theme: .light))
        XCTAssertFalse(sections.showsAutoThemeBlock(theme: .dark))
        XCTAssertFalse(sections.showsAutoThemeBlock(theme: .darkdim))
        XCTAssertFalse(sections.showsAutoThemeBlock(theme: .sepia))
    }

    /// Settings → Display may edit the schedule; the reader's own sheet shows a read-only summary
    /// card instead. Hosting the editor in the reader was the bug this rule prevents.
    func testAutoThemeEditorOnlyRendersInAnEditableHost() {
        let sections = ReaderSettingsSections.shared
        XCTAssertTrue(sections.showsAutoThemeEditor(theme: .auto_, scheduleEditable: true))
        XCTAssertFalse(sections.showsAutoThemeEditor(theme: .auto_, scheduleEditable: false))
        XCTAssertFalse(
            sections.showsAutoThemeEditor(theme: .light, scheduleEditable: true),
            "no Auto theme means no editor, however editable the host is"
        )
    }

    /// Following the app theme has no times to pick, so the day/night schedule rows collapse.
    func testScheduleEditorIsHiddenWhenAutoFollowsTheAppTheme() {
        let sections = ReaderSettingsSections.shared
        XCTAssertTrue(sections.showsScheduleEditor(autoMode: .schedule))
        XCTAssertFalse(sections.showsScheduleEditor(autoMode: .apptheme))
    }

    /// "Colored chapter map" is a sub-setting: it greys out when the chapter map itself is off.
    func testColoredChapterMapIsDisabledWhenTheChapterMapIsOff() {
        let sections = ReaderSettingsSections.shared
        XCTAssertTrue(sections.coloredChapterMapEnabled(showChapterMap: true))
        XCTAssertFalse(sections.coloredChapterMapEnabled(showChapterMap: false))
    }

    // MARK: - Row summaries (ReaderSettingsSummariesTest)

    /// `doCopy` on a Kotlin data class exports every parameter as required, so build the variants
    /// from `defaults()` through one helper instead of repeating 23 arguments per test.
    private func prefs(
        fontFamily: ReaderFontFamily? = nil,
        fontSize: Float? = nil,
        margins: Float? = nil,
        theme: ReaderTheme? = nil,
        orientation: ReaderOrientation? = nil,
        showChapterMap: Bool? = nil,
        autoReaderThemeMode: AutoReaderThemeMode? = nil,
        showAutoScroll: Bool? = nil,
        autoScrollWpm: Int32? = nil,
        showCadence: Bool? = nil,
        cadenceWpm: Int32? = nil
    ) -> FormattingPreferences {
        let base = FormattingPreferences.companion.defaults()
        return base.doCopy(
            fontSize: fontSize ?? base.fontSize,
            theme: theme ?? base.theme,
            fontFamily: fontFamily ?? base.fontFamily,
            lineSpacing: base.lineSpacing,
            margins: margins ?? base.margins,
            orientation: orientation ?? base.orientation,
            showChapterMap: showChapterMap ?? base.showChapterMap,
            coloredChapterMap: base.coloredChapterMap,
            showReadingProgressLabels: base.showReadingProgressLabels,
            showCurrentChapterLabel: base.showCurrentChapterLabel,
            showReadingTimeEstimate: base.showReadingTimeEstimate,
            doublePageSpread: base.doublePageSpread,
            forcePaginatedInLandscape: base.forcePaginatedInLandscape,
            justifyText: base.justifyText,
            autoReaderThemeMode: autoReaderThemeMode ?? base.autoReaderThemeMode,
            appThemeReaderThemes: base.appThemeReaderThemes,
            themeSchedule: base.themeSchedule,
            autoScrollWpm: autoScrollWpm ?? base.autoScrollWpm,
            showAutoScroll: showAutoScroll ?? base.showAutoScroll,
            cadenceWpm: cadenceWpm ?? base.cadenceWpm,
            showCadence: showCadence ?? base.showCadence,
            cadenceHighlightColor: base.cadenceHighlightColor,
            cadencePlatformSupported: base.cadencePlatformSupported
        )
    }

    /// The Formatting row previews font, size and margins — the size as a whole percentage.
    func testFormattingSummaryShowsFontSizeAndMargins() {
        XCTAssertEqual(
            ReaderSettingsSummaries.shared.formattingSummary(
                prefs: prefs(fontFamily: .serif, fontSize: 1.1, margins: 1.0)
            ),
            "Serif · 110% · Normal margins"
        )
    }

    /// A concrete theme prints its own name; Auto prints "Auto <mode>". Orientation and the
    /// chapter-map flag complete the line.
    func testDisplaySummaryCoversConcreteThemeAutoThemeAndMapOff() {
        let summaries = ReaderSettingsSummaries.shared
        XCTAssertEqual(summaries.displaySummary(prefs: prefs(), includeChapterMap: true), "Light · Paginated · map on")
        XCTAssertEqual(summaries.displaySummary(prefs: prefs(theme: .auto_), includeChapterMap: true), "Auto Time based · Paginated · map on")
        XCTAssertEqual(
            summaries.displaySummary(prefs: prefs(theme: .auto_, autoReaderThemeMode: .apptheme), includeChapterMap: true),
            "Auto App theme · Paginated · map on"
        )
        XCTAssertEqual(
            summaries.displaySummary(prefs: prefs(orientation: .vertical, showChapterMap: false), includeChapterMap: true),
            "Light · Scroll · map off"
        )
        XCTAssertEqual(summaries.displaySummary(prefs: prefs(orientation: .continuous), includeChapterMap: true), "Light · Continuous · map on")
    }

    /// Slider values are shown as words, not raw floats. The bucket boundaries are the claim.
    func testLineSpacingAndMarginWordsBucketTheirRanges() {
        let summaries = ReaderSettingsSummaries.shared
        XCTAssertEqual(summaries.lineSpacingWord(value: 1.0), "Tight")
        XCTAssertEqual(summaries.lineSpacingWord(value: 1.2), "Compact")
        XCTAssertEqual(summaries.lineSpacingWord(value: 1.5), "Normal")
        XCTAssertEqual(summaries.lineSpacingWord(value: 1.6), "Comfortable")
        XCTAssertEqual(summaries.lineSpacingWord(value: 1.8), "Roomy")
        XCTAssertEqual(summaries.lineSpacingWord(value: 2.0), "Spacious")

        XCTAssertEqual(summaries.marginsWord(value: 0.2), "Edge")
        XCTAssertEqual(summaries.marginsWord(value: 0.7), "Tight")
        XCTAssertEqual(summaries.marginsWord(value: 1.0), "Normal")
        XCTAssertEqual(summaries.marginsWord(value: 1.5), "Comfortable")
        XCTAssertEqual(summaries.marginsWord(value: 2.0), "Roomy")
        XCTAssertEqual(summaries.marginsWord(value: 3.0), "Wide")
    }

    func testBehaviorSummaryRendersBothToggleCombinations() {
        let summaries = ReaderSettingsSummaries.shared
        XCTAssertEqual(
            summaries.behaviorSummary(keepScreenOn: true, volumeKeyNavigationEnabled: false),
            "Keep screen on · volume nav off"
        )
        XCTAssertEqual(
            summaries.behaviorSummary(keepScreenOn: false, volumeKeyNavigationEnabled: true),
            "Keep screen off · volume nav on"
        )
    }

    func testAutoScrollAndCadenceSummariesShowWpmOnlyWhenEnabled() {
        let summaries = ReaderSettingsSummaries.shared
        XCTAssertEqual(summaries.autoScrollSummary(prefs: prefs()), "Off")
        XCTAssertEqual(summaries.cadenceSummary(prefs: prefs()), "Off")
        XCTAssertEqual(
            summaries.autoScrollSummary(prefs: prefs(showAutoScroll: true, autoScrollWpm: 300)),
            "Hands-free scroll — 300 wpm"
        )
        XCTAssertEqual(
            summaries.cadenceSummary(prefs: prefs(showCadence: true, cadenceWpm: 180)),
            "Sentence highlight — 180 wpm"
        )
    }

    /// Times are zero-padded. Android got that from `String.format`, which does not exist in
    /// Kotlin/Native — the shared port pads by hand, and a regression would print "Day 7:5".
    func testAutoScheduleSummaryZeroPadsTimesAndNamesBothThemes() {
        let schedule = ThemeSchedule(
            dayStart: LocalMinuteTime(hour: 7, minute: 5),
            nightStart: LocalMinuteTime(hour: 21, minute: 30),
            dayTheme: .light,
            nightTheme: .darkdim
        )
        XCTAssertEqual(
            ReaderSettingsSummaries.shared.autoScheduleSummary(schedule: schedule),
            "Day 07:05 · Light → Night 21:30 · Dim"
        )
    }

    /// In App-theme mode the summary describes the light/dark pairing instead of the schedule.
    func testAutoThemeSummarySwitchesBetweenScheduleAndAppThemeModes() {
        let summaries = ReaderSettingsSummaries.shared
        let schedule = ThemeSchedule(
            dayStart: LocalMinuteTime(hour: 6, minute: 0),
            nightStart: LocalMinuteTime(hour: 20, minute: 0),
            dayTheme: .sepia,
            nightTheme: .dark
        )
        XCTAssertEqual(
            summaries.autoThemeSummary(
                schedule: schedule,
                autoMode: .schedule,
                appThemeReaderThemes: AppThemeReaderThemes(lightTheme: .light, darkTheme: .dark)
            ),
            "Day 06:00 · Sepia → Night 20:00 · Dark"
        )
        XCTAssertEqual(
            summaries.autoThemeSummary(
                schedule: schedule,
                autoMode: .apptheme,
                appThemeReaderThemes: AppThemeReaderThemes(lightTheme: .sepia, darkTheme: .darkdim)
            ),
            "Light app · Sepia → Dark app · Dim"
        )
    }
}


# 12 — Reader Settings Sheet (iOS)

## 12.1 Reader settings sheet shows Formatting and Display tabs (no Behavior tab)

**Given** the reader settings sheet is open.
**Then** the sheet shows exactly two tabs: "Formatting" and "Display". No "Behavior" tab.

**Coverage:** `ReaderSettingsSectionsTest.readerSheet_showsAllThreeTabs_andBehaviorReachable`

**iOS gap:** Compose-only sheet; verified manually via Xcode build.

## 12.2 Display section shows schedule editor when host is editable

**Given** the reader theme is set to Auto and the settings host allows schedule editing.
**When** the Display section renders.
**Then** the "Day starts at" time picker is visible.

**Coverage:** `ReaderSettingsSectionsTest.displaySection_editableHostShowsScheduleEditor`

## 12.3 Display section shows read-only summary in reader host

**Given** the reader theme is set to Auto and the settings are shown from within the reader (not Settings).
**When** the Display section renders.
**Then** an "Edit Auto in Settings → Display" note appears instead of the schedule editor.

**Coverage:** `ReaderSettingsSectionsTest.displaySection_readerHostShowsReadOnlySummaryNotEditor`

## 12.4 App theme Auto mode hides the schedule editor

**Given** the auto theme mode is "App theme" (system).
**When** the Display section renders.
**Then** the schedule editor is hidden; "App theme" and "Light app theme" labels are visible.

**Coverage:** `ReaderSettingsSectionsTest.displaySection_appThemeAutoModeHidesScheduleEditor`

## 12.5 PDF capabilities hide font and typography controls

**Given** the reader settings sheet is shown for a PDF book.
**When** the Formatting tab is active.
**Then** font family, font size, justify text, and line spacing controls are hidden. Only Margins remains.

**Coverage:** `ReaderSettingsSheetCapabilitiesTest.pdfCaps_hideFontFamilyAndReadingModeAndDoublePage`

## 12.6 PDF capabilities hide reading mode and double page

**Given** the reader settings sheet is shown for a PDF book.
**When** the Display tab is active.
**Then** "Reading mode", "Double page in landscape", "Theme", and position overlay toggles are all hidden. Chapter map remains.

**Coverage:** `ReaderSettingsSheetCapabilitiesTest.pdfCaps_hideFontFamilyAndReadingModeAndDoublePage`

## 12.7 EPUB capabilities show font family and reading mode

**Given** the reader settings sheet is shown for an EPUB book.
**When** both Formatting and Display tabs are active.
**Then** font family, text controls, reading mode, double page, theme, and position overlays are all visible.

**Coverage:** `ReaderSettingsSheetCapabilitiesTest.epubCaps_showFontFamilyAndReadingModeAndDoublePage`

## 12.8 Behavior section row height is consistent

**Given** the reader settings Behavior section renders multiple rows.
**Then** each row has a consistent height (no row is clipped or overflowing its bounds).

**Coverage:** `BehaviorSectionRowHeightTest`

**iOS gap:** UI-only layout check; verified manually via Xcode build.

## 12.9 Readaloud play drops reader into immersive mode

**Given** the reader is showing and readaloud starts playing.
**When** the isReadaloudPlaying flag changes to true.
**Then** the system bars are hidden and the reader enters immersive mode.

**Coverage:** `ImmersiveOnReadaloudPlayTest.startingPlayback_entersImmersive`

**iOS gap:** iOS handles system bar visibility differently (safe area insets); iOS immersive behaviour verified manually via Xcode build.

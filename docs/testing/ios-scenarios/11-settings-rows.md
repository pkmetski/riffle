# 11 — Settings Rows (iOS)

## 11.1 Swipe end-to-start on source row invokes delete

**Given** a configured source row is visible in Settings.
**When** the user swipes from right to left (end-to-start) on the row.
**Then** the delete callback is invoked.

**Coverage:** `SwipeToDeleteRowTest.endToStartSwipe_invokesOnDelete`

**iOS gap:** Swipe-to-delete gesture is a SwiftUI List behaviour; verified manually via Xcode build.

## 11.2 Swipe start-to-end does not invoke delete

**Given** a configured source row is visible in Settings.
**When** the user swipes from left to right (start-to-end) on the row.
**Then** the delete callback is NOT invoked.

**Coverage:** `SwipeToDeleteRowTest.startToEndSwipe_doesNotInvokeOnDelete`

## 11.3 Cadence settings panel renders key controls

**Given** the Cadence drill-in panel is open in Reader Settings.
**Then** the panel shows: title, About blurb, "Show Cadence" toggle, Default speed control, and highlight colour picker with four swatches (Yellow, Green, Blue, Red).

**Coverage:** `CadenceSettingsPanelTest.cadence_panel_renders_hero_about_toggle_speed_and_color`

**iOS gap:** Compose-only panel. The shared `FormattingPreferences.cadenceHighlightColor` field (KMP) is tested via enum existence check.

## 11.4 Chitanka source row renders correctly

**Given** a Chitanka source is configured.
**When** the Settings source list renders.
**Then** the Chitanka source row is visible with the correct source type.

**Coverage:** `ChitankaSourceRowTest`

**iOS gap:** UI-only; verified manually via Xcode build.

## 11.5 Active ABS server shows library visibility switches

**Given** an active Audiobookshelf server has two libraries (e.g. "Fiction", "Non-fiction").
**When** the server settings expansion is open.
**Then** both library names appear with visibility toggle switches.

**Coverage:** `ServerSettingsExpansionTest.activeAbsServerShowsLibrarySwitches`

## 11.6 Inactive server disables library switches

**Given** a server is inactive.
**When** the server settings expansion is open.
**Then** the library visibility switches are present but disabled.

**Coverage:** `ServerSettingsExpansionTest`

## 11.7 Audio playback speed persists across sessions

**Given** the user selects a non-default audio playback speed in Settings.
**When** the app is reopened.
**Then** the selected speed is still set.

**Coverage:** `AudioPlaybackSpeedPersistenceTest`

**iOS gap:** Persistence is backed by shared KMP DataStore; verified via `AudioPlaybackSpeedPersistenceTest`-equivalent logic; UI-only verification done manually.

## 11.8 Local files source row shows trash/remove action

**Given** a Local Files source is configured.
**When** the user interacts with the source row.
**Then** a delete/trash option is available.

**Coverage:** `LocalFilesSourceRowTrashTest`

**iOS gap:** UI-only; verified manually via Xcode build.

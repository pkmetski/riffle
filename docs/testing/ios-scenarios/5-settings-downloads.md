# Scenario 5 — iOS Settings and Downloads screens

Covers the shared `SettingsScreen`, `SettingsViewModel`, `DownloadsScreen`, and `DownloadsViewModel`
implemented in `shared/src/commonMain/kotlin/com/riffle/shared/settings/` and `.../downloads/`.

## Scenarios

### 5.1 Settings screen navigation via drawer
- Launch the app with at least one source configured.
- Open the navigation drawer.
- Tap **Settings**.
- **Expected**: the Settings screen appears with a "Settings" heading. The Sources section lists
  the configured source(s). The Appearance section shows the current AppTheme (System/Light/Dark).
  The Downloads drawer row remains visible.

### 5.2 App theme picker
- Navigate to Settings (via drawer).
- In the Appearance section, tap **Light**.
- **Expected**: the active selection updates to "Light" (highlighted in blue).
- Tap **Dark**.
- **Expected**: the active selection updates to "Dark".
- Tap **System**.
- **Expected**: the active selection updates to "System".

### 5.3 Source removal from Settings
- Launch the app with at least two sources configured.
- Navigate to Settings.
- In the Sources section, tap **Remove** next to one source.
- **Expected**: that source disappears from the list.

### 5.4 Downloads screen navigation via drawer
- Open the navigation drawer.
- Tap **Downloads**.
- **Expected**: the Downloads screen appears with a "Downloads" heading.
  If no files are downloaded, "No downloaded or cached items." is shown.

### 5.5 Downloads screen with items (stub)
- On a device with downloaded items available via `DownloadsRepository`, navigate to Downloads.
- **Expected**: the Downloaded section lists each artifact with its media-type label and size.
  The Cached section lists cached-only items. Each row has a **Remove** button.

### 5.6 Remove a download
- On a device with at least one downloaded item, navigate to Downloads.
- Tap **Remove** next to an item.
- **Expected**: the item disappears from the list after the operation completes.

### 5.7 Drawer active-section highlight
- Navigate to Settings via the drawer. Reopen the drawer.
- **Expected**: the Settings row has a grey/highlighted background.
- Tap a library to navigate back to the Library section. Reopen the drawer.
- **Expected**: Settings row is no longer highlighted; the selected library row is highlighted.

## XCTest coverage

Implemented in `iosApp/iosAppTests/SettingsDownloadsTests.swift`.

Scenarios 5.5 and 5.6 require a non-empty `DownloadsRepository`; on iOS the current
`IosNoOpDownloadsRepository` always returns empty lists. These scenarios are verified
via the Android harness tests and will be revisited when a real iOS storage backend
is implemented.

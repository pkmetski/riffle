# iOS Scenario 6: SettingsViewModel (shared KMP)

**Feature:** SettingsViewModel ported from `:app` to `feature:settings` commonMain (issue #951)

## Background

`SettingsViewModel` now lives in `feature:settings/commonMain` and is shared across Android and iOS. iOS uses no-op implementations for Android-only features (local files, crash reports, APK updates, volume key navigation, wake lock).

## Test Scenarios

### 6.1 App theme changes reflect in SettingsViewModel

- SettingsViewModel.appTheme emits the current theme (System/Light/Dark)
- Calling setAppTheme() updates the StateFlow

### 6.2 Servers list observable

- SettingsViewModel.servers emits the current server list
- Removing a server via removeServer() removes it from the list

### 6.3 Annotation sync row state

- annotationSyncRow emits AnnotationSyncRowState with badge and subtitle
- When no WebDAV config is set, badge is Local and sub is NotConfigured

### 6.4 Library visibility and ordering

- libraryUiItemsBySource reflects library order per source
- setLibraryVisible updates visibility store

## iOS XCTest Coverage

See `iosApp/iosAppTests/SettingsViewModelTests.swift` for implementation of all scenarios above using the shared KMP SettingsViewModel via Koin DI.

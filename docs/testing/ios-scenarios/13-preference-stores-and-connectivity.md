# 13 — Preference Stores and Connectivity (iOS)

## 13.1 IosConnectivityObserver initializes without error and reports online in a connected environment

**Given** a newly created `IosConnectivityObserver`.
**Then** `isOnline.value` is `true` (the simulator/device running tests has network access).

**Coverage:** `PreferenceStoresAndConnectivityTests.testConnectivityObserverInitializesOnline`

## 13.2 IosAppThemeStoreImpl round-trips the app theme through NSUserDefaults

**Given** a freshly created `IosAppThemeStoreImpl` (clears its key first).
**When** `setAppTheme(AppTheme.Dark)` is called.
**Then** a second instance reading from the same NSUserDefaults key returns `AppTheme.Dark`.

**Coverage:** `PreferenceStoresAndConnectivityTests.testAppThemeStoreRoundTrips`

## 13.3 IosListeningPreferencesStoreImpl persists playback speed

**Given** a freshly created `IosListeningPreferencesStoreImpl`.
**When** `setDefaultPlaybackSpeed(1.5f)` is called.
**Then** a second instance reading from NSUserDefaults reflects `1.5f`.

**Coverage:** `PreferenceStoresAndConnectivityTests.testListeningPreferencesSpeedRoundTrips`

## 13.4 IosLibraryOrderPreferencesStoreImpl persists library ordering per source

**Given** a freshly created `IosLibraryOrderPreferencesStoreImpl`.
**When** `setLibraryOrder("src1", ["lib-b", "lib-a"])` is called.
**Then** a second instance reading from NSUserDefaults returns `["lib-b", "lib-a"]` for `"src1"`.

**Coverage:** `PreferenceStoresAndConnectivityTests.testLibraryOrderRoundTrips`

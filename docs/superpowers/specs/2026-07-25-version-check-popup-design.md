# Version-check popup — design spec

**Date:** 2026-07-25
**Branch:** `pkmetski/version-check-popup`

## Overview

Show a startup dialog when a newer GitHub release is available. The dialog lists all missed releases with their changelogs. The user can update in-app (existing download/install flow), dismiss until the next cold start, or permanently ignore the current version until a newer one appears. A toggle in Settings controls whether the check runs at all; the App version section in Settings also shows the full release changelog history.

---

## Data layer

### New domain model — `ReleaseInfo`

```kotlin
// core/domain
data class ReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val downloadUrl: String,  // APK asset URL; empty string for releases without an APK
    val sizeBytes: Long,      // 0 when no APK asset
)
```

`changelog` is the raw GitHub release `body` field (markdown). Rendered as plain text in-app — no markdown renderer is available. `downloadUrl`/`sizeBytes` are only meaningful for releases that have an APK asset; they are ignored when rendering the Settings changelog list.

### `AppUpdateRepository` — new method

```kotlin
suspend fun listReleasesSince(sinceVersionCode: Int): List<ReleaseInfo>
```

Returns all non-draft, non-prerelease GitHub releases whose version code is strictly greater than `sinceVersionCode`, ordered newest-first. Pass `0` to retrieve the full recent history (used by the Settings changelog section). The first element is the update target for the startup popup.

### `GitHubReleaseApi` — additions

- `body: String` field added to the internal `ReleaseResponse` deserialization model.
- New `suspend fun listReleases(repo: String): List<GitHubRelease>` method — fetches `per_page=20`, skips drafts/prereleases, returns all matching releases with their bodies. `GitHubRelease` gains a `body: String` field.
- Existing `latestRelease()` is unchanged; the startup check is migrated to use `listReleasesSince()`.

### New `AppUpdatePreferencesStore`

**Interface** (`core/domain`):
```kotlin
interface AppUpdatePreferencesStore {
    val autoUpdateEnabled: Flow<Boolean>
    val ignoredVersionCode: Flow<Int>
    suspend fun setAutoUpdateEnabled(value: Boolean)
    suspend fun setIgnoredVersionCode(value: Int)
}
```

**Implementation** (`core/data`): factory function `AppUpdatePreferencesStore(dataStore)` following the existing `PrefCodecs` / `preferenceStore` pattern. Backed by a new DataStore file named `"app_update_preferences"` with two keys:
- `"auto_update_enabled"` — boolean, default `true`
- `"ignored_version_code"` — int, default `0`

DI: new `@Provides` in `PreferencesModule` with a dedicated `@Named("app_update")` DataStore extension property.

---

## ViewModel layer

### New `StartupUpdateViewModel` (`@HiltViewModel`)

Hoisted at `MainScreen` level (before the `NavHost`) so it is effectively activity-scoped.

**Dependencies injected:** `AppUpdateRepository`, `AppUpdatePreferencesStore`.  
`BuildConfig.VERSION_CODE` is read as a compile-time constant (`:app` module).

**Startup flow (in `init {}`):**
1. Read `autoUpdateEnabled` and `ignoredVersionCode` from prefs (first value via `.first()`).
2. If `autoUpdateEnabled` is false → stay idle.
3. Call `listReleasesSince(BuildConfig.VERSION_CODE)`.
4. If the list is empty → stay idle.
5. If `releases.first().versionCode == ignoredVersionCode` → stay idle.
6. Otherwise → set `dialogState` to `StartupUpdateDialogState(releases, availableUpdate)`.

**State:**
```kotlin
data class StartupUpdateDialogState(
    val releases: List<ReleaseInfo>,  // all missed versions, newest first
    // AvailableUpdate is derived from releases.first() inside the VM before exposing state
)
```

`AvailableUpdate` is constructed from `releases.first()` (using its `downloadUrl` and `sizeBytes` fields) before being stored in `dialogState`, so `startUpdate()` receives a fully-formed `AvailableUpdate`:

```kotlin
data class StartupUpdateDialogState(
    val releases: List<ReleaseInfo>,
    val update: AvailableUpdate,           // constructed from releases.first()
)

val dialogState: StateFlow<StartupUpdateDialogState?>   // null = no dialog
val downloadState: StateFlow<UpdateDownloadState?>      // null = not downloading
```

**Actions:**
- `ignoreVersion(versionCode: Int)` — writes `ignoredVersionCode` pref, clears `dialogState`.
- `startUpdate(update: AvailableUpdate)` — collects `downloadAndInstall()` into `downloadState`.
- `dismissDialog()` — clears `dialogState` without writing the ignore pref (dialog reappears next cold start).

### `SettingsViewModel` — additions

Three small additions; no structural change.

- **Injected:** `AppUpdatePreferencesStore`
- `autoUpdateEnabled: StateFlow<Boolean>` (from pref flow)
- `fun setAutoUpdateEnabled(value: Boolean)` (writes pref)
- `releaseHistory: StateFlow<List<ReleaseInfo>>` — populated once in `init {}` by calling `listReleasesSince(0)` into a `MutableStateFlow`. Empty list while loading; stays empty on network failure (no error surface in the changelog section).

---

## UI layer

### New `UpdateAvailableDialog` composable

An `AlertDialog` with:

**Body:** `LazyColumn` — for each `ReleaseInfo` (newest first):
- Bold text: `v${info.versionName}`
- Body text: `info.changelog` (raw, plain text)

**Buttons (three):**
- **"Later"** (dismiss) — calls `onDismiss()`; dialog reappears next cold start. Hidden while downloading.
- **"Ignore this version"** — calls `onIgnore(releases.first().versionCode)`; hidden while downloading.
- **"Update"** — calls `onUpdate(state.update)`. While `downloadState != null`, this button is replaced by `CircularProgressIndicator(progress = percent/100f)` + status text (`"Downloading… X%"` or `"Starting installer…"`). Dialog is non-dismissable during download (`properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)`).

### `MainScreen.kt` — addition

Before the `NavHost`:
```kotlin
val startupVm: StartupUpdateViewModel = hiltViewModel()
val dialogState by startupVm.dialogState.collectAsState()
val downloadState by startupVm.downloadState.collectAsState()
dialogState?.let { state ->
    UpdateAvailableDialog(
        state = state,
        downloadState = downloadState,
        onIgnore = startupVm::ignoreVersion,
        onUpdate = startupVm::startUpdate,
        onDismiss = startupVm::dismissDialog,
    )
}
```

### `AppVersionSection.kt` — additions

Below the existing update-check row, two new items:

1. `Switch` `ListItem` — headline `"Check for updates on startup"`, checked state bound to `autoUpdateEnabled`, `onCheckedChange` calls `onSetAutoUpdateEnabled`.
2. `SettingsSectionHeader("Changelog")` — header for the changelog section below.

`AppVersionSection` signature gains `autoUpdateEnabled: Boolean` and `onSetAutoUpdateEnabled: (Boolean) -> Unit` parameters.

### New `ChangelogSection.kt` composable

Renders `releaseHistory: List<ReleaseInfo>` as a vertical list of version-header + changelog-body cards, identical layout to the dialog body. States:
- Empty list → single `CircularProgressIndicator` (loading).
- Non-empty → the card list.

No explicit error state is surfaced (the section simply stays in loading appearance on network failure).

---

## DI wiring summary

| New artifact | Module |
|---|---|
| `AppUpdatePreferencesStore` interface | `core/domain` |
| `AppUpdatePreferencesStore` factory | `core/data` |
| `"app_update"` DataStore `@Provides` | `core/data` `PreferencesModule` |
| `ReleaseInfo` model | `core/domain` |
| `listReleasesSince()` on `AppUpdateRepository` | `core/domain` (interface) + `core/data` (impl) |
| `listReleases()` on `GitHubReleaseApi` + `body` field | `core/network` |
| `StartupUpdateViewModel` | `app` |
| `UpdateAvailableDialog` | `app` |
| `ChangelogSection` | `app` |

---

## Test plan

- **`GitHubReleaseApiTest`** — add cases for `listReleases()`: happy path returns bodies; draft/prerelease entries skipped; HTTP error returns empty.
- **`AppUpdateRepositoryImplTest`** — `listReleasesSince(N)` filters correctly; `listReleasesSince(0)` returns all; unrecognisable tags skipped.
- **`StartupUpdateViewModelTest`** — auto-update disabled → no dialog; latest == ignored → no dialog; new version available → dialog with correct releases list; `ignoreVersion` writes pref + clears dialog; `dismissDialog` clears dialog without writing pref.
- **`SettingsViewModelTest`** — `setAutoUpdateEnabled` persists; `releaseHistory` populated from `listReleasesSince(0)`.

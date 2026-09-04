# iOS Settings & Downloads Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Android Settings and Downloads sections to iOS by implementing shared Compose Multiplatform screens in `commonMain` and wiring them into the existing shared navigation drawer.

**Architecture:** The iOS app is pure Compose Multiplatform — there are no SwiftUI screens. All new screens go in `shared/src/commonMain`. A top-level `AppSection` state in `HomeScreen.kt` replaces direct routing to the library host, adding `Settings` and `Downloads` as peer destinations alongside `Library`. The new `SettingsViewModel` in `commonMain` is separate from the Android-only `SettingsViewModel` in `app/` — Android keeps its own, iOS uses the shared one. Several domain interfaces (`FormattingPreferences`, `AppThemeStore`, `DownloadsRepository`) live in `jvmMain` today and must be moved to `commonMain` first; iOS gets no-op implementations wired in `Koin.kt`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, Koin, StateFlow/Flow, `NSFileManager` (iOS storage), XCTest (iOS UI tests)

**Spec:** Design decided in brainstorming conversation (2026-09-04) — Settings full surface with Android-only sections behind `expect/actual`; Downloads screen with iOS no-op repository.

## Global Constraints

- All new production code in `shared/src/commonMain` must have zero `android.*` / `androidx.*` / `java.*` imports (enforced by `checkNoAndroidImports` CI task).
- All new `core:domain` interfaces must land in `commonMain`; impls in `androidMain` or `iosMain`.
- No `Server`-prefixed identifiers or bare `serverId` in new Kotlin files (`checkNoServerReferences`).
- Log via `Logger` + `LogChannel`; never `Log.d("RIFFLE_*", …)` literals.
- Every new `@Test` needs an entry in the `migrateFullChain` test if it's a migration test (not applicable here), and must not be deleted without a `Removed-test:` commit trailer.
- Run `./gradlew test jvmTest` (not just `test`) before claiming green — KMP modules need both tasks.
- No `java.time.*` in `commonMain` — use the `LocalMinuteTime` value class introduced in Task 1.
- Export `JAVA_HOME` to the Android Studio JBR before running Gradle: `export JAVA_HOME="$(dirname $(dirname $(readlink -f $(which java))))"` or the Studio path.

---

## File Map

**New files:**
| File | Responsibility |
|------|---------------|
| `core/domain/src/commonMain/.../LocalMinuteTime.kt` | KMP-compatible hour/minute time value replacing `java.time.LocalTime` in `FormattingPreferences` |
| `core/domain/src/commonMain/.../FormattingPreferences.kt` | Moved from `jvmMain`; uses `LocalMinuteTime` instead of `LocalTime` |
| `core/domain/src/commonMain/.../FormattingPreferencesStore.kt` | Moved from `jvmMain` |
| `core/domain/src/commonMain/.../AppTheme.kt` | Moved from `jvmMain` |
| `core/domain/src/commonMain/.../DownloadsRepository.kt` | Moved from `jvmMain`; `StoredItemArtifact`, `StoredItemRef`, `StoredMediaType` |
| `shared/src/iosMain/.../IosDownloadsRepositoryImpl.kt` | Stub iOS impl (returns empty lists; no persistent download tracking yet) |
| `shared/src/commonMain/.../feature/downloads/DownloadsViewModel.kt` | Observes downloaded/cached items, cache settings |
| `shared/src/commonMain/.../feature/downloads/DownloadsScreen.kt` | Two-section list (Downloaded + Cached) |
| `shared/src/commonMain/.../feature/settings/SettingsViewModel.kt` | Shared settings state (sources, prefs stores available in commonMain) |
| `shared/src/commonMain/.../feature/settings/SettingsScreen.kt` | Scrollable section list + internal `SettingsNav` routing |
| `shared/src/commonMain/.../feature/settings/sections/SourcesSection.kt` | ABS source rows; web source rows |
| `shared/src/commonMain/.../feature/settings/sections/AppearanceSection.kt` | App theme picker (Light/Dark/System) |
| `shared/src/commonMain/.../feature/settings/sections/ReadingSection.kt` | Formatting/Display/AutoScroll/Cadence panel launchers |
| `shared/src/commonMain/.../feature/settings/sections/ComicsSection.kt` | Comic display panel launcher |
| `shared/src/commonMain/.../feature/settings/sections/ListeningSection.kt` | Listening panel launcher |
| `shared/src/commonMain/.../feature/settings/sections/ReadaloudSection.kt` | Readaloud drill-in row |
| `shared/src/commonMain/.../feature/settings/sections/AnnotationsSyncSection.kt` | Annotations sync badge row + drill-in |
| `shared/src/commonMain/.../feature/settings/sections/AppVersionSection.kt` | Version string row |
| `shared/src/commonMain/.../feature/settings/panels/FormattingSettingsPanel.kt` | Bottom-sheet: font, size, line height, margins, orientation |
| `shared/src/commonMain/.../feature/settings/panels/DisplaySettingsPanel.kt` | Bottom-sheet: scroll mode, reader theme, brightness |
| `shared/src/commonMain/.../feature/settings/panels/AutoScrollSettingsPanel.kt` | Bottom-sheet: auto-scroll WPM |
| `shared/src/commonMain/.../feature/settings/panels/CadenceSettingsPanel.kt` | Bottom-sheet: cadence WPM, highlight color |
| `shared/src/commonMain/.../feature/settings/panels/ListeningPreferencesPanel.kt` | Bottom-sheet: speed, skip/rewind intervals |
| `shared/src/commonMain/.../feature/settings/panels/ComicDisplaySettingsPanel.kt` | Bottom-sheet: comic reading direction |
| `shared/src/commonMain/.../feature/settings/readaloud/ReadaloudSettingsScreen.kt` | Readaloud highlight color + Storyteller source list |
| `shared/src/commonMain/.../feature/settings/annotationsync/AnnotationsSyncSettingsScreen.kt` | WebDAV URL/user/pass fields |
| `docs/testing/ios-scenarios/N-settings-downloads.md` | XCTest scenario spec |
| `iosApp/iosAppTests/SettingsTests.swift` | XCTest UI tests for Settings nav |
| `iosApp/iosAppTests/DownloadsTests.swift` | XCTest UI tests for Downloads screen |

**Modified files:**
| File | Change |
|------|--------|
| `core/domain/src/jvmMain/.../FormattingPreferences.kt` | Delete (moved to commonMain) |
| `core/domain/src/jvmMain/.../FormattingPreferencesStore.kt` | Delete (moved to commonMain) |
| `core/domain/src/jvmMain/.../AppTheme.kt` | Delete (moved to commonMain) |
| `core/domain/src/jvmMain/.../DownloadsRepository.kt` | Delete (moved to commonMain) |
| `core/domain/src/jvmMain/.../LocalStore.kt` | No change — stays in jvmMain (Android-only) |
| `core/data/src/androidMain/.../FormattingPreferencesStoreImpl.kt` | Update `LocalTime` → `LocalMinuteTime` conversions |
| `core/data/src/androidMain/.../AppearanceCoordinatorImpl.kt` | Update `LocalTime` → `LocalMinuteTime` |
| `core/common/src/jvmMain/.../TimeProvider.kt` | Update to use `LocalMinuteTime` |
| `shared/src/iosMain/.../library/IosNoOpImpls.kt` | Add `IosNoOpFormattingPreferencesStore`, `IosNoOpAppThemeStore` |
| `shared/src/iosMain/.../Koin.kt` | Register `DownloadsViewModel`, `SettingsViewModel`, `IosDownloadsRepositoryImpl`, `IosNoOpFormattingPreferencesStore`, `IosNoOpAppThemeStore` |
| `shared/src/commonMain/.../HomeScreen.kt` | Add `AppSection` top-level nav, Settings/Downloads rows in drawer |

---

## Task 1: Move `FormattingPreferences` + `FormattingPreferencesStore` to `commonMain`

**Files:**
- Create: `core/domain/src/commonMain/kotlin/com/riffle/core/domain/LocalMinuteTime.kt`
- Create: `core/domain/src/commonMain/kotlin/com/riffle/core/domain/FormattingPreferences.kt`
- Create: `core/domain/src/commonMain/kotlin/com/riffle/core/domain/FormattingPreferencesStore.kt`
- Delete: `core/domain/src/jvmMain/kotlin/com/riffle/core/domain/FormattingPreferences.kt`
- Delete: `core/domain/src/jvmMain/kotlin/com/riffle/core/domain/FormattingPreferencesStore.kt`
- Modify: `core/data/src/androidMain/kotlin/com/riffle/core/data/FormattingPreferencesStoreImpl.kt`
- Modify: `core/data/src/androidMain/kotlin/com/riffle/core/data/AppearanceCoordinatorImpl.kt`
- Modify: `core/common/src/jvmMain/kotlin/com/riffle/core/common/TimeProvider.kt`

**Interfaces:**
- Produces: `LocalMinuteTime(hour: Int, minute: Int)` in `com.riffle.core.domain` — used by Tasks 10, 11
- Produces: `FormattingPreferencesStore` in `commonMain` — used by Task 6

- [ ] **Step 1: Create `LocalMinuteTime` in `commonMain`**

```kotlin
// core/domain/src/commonMain/kotlin/com/riffle/core/domain/LocalMinuteTime.kt
package com.riffle.core.domain

data class LocalMinuteTime(val hour: Int, val minute: Int) : Comparable<LocalMinuteTime> {
    override fun compareTo(other: LocalMinuteTime): Int =
        compareValuesBy(this, other, LocalMinuteTime::hour, LocalMinuteTime::minute)

    companion object {
        fun of(hour: Int, minute: Int): LocalMinuteTime = LocalMinuteTime(hour, minute)
    }
}
```

- [ ] **Step 2: Copy `FormattingPreferences.kt` from `jvmMain` to `commonMain`, replacing `LocalTime` with `LocalMinuteTime`**

Open `core/domain/src/jvmMain/kotlin/com/riffle/core/domain/FormattingPreferences.kt`. Copy it to `core/domain/src/commonMain/kotlin/com/riffle/core/domain/FormattingPreferences.kt`. In the new file:
1. Remove `import java.time.LocalTime`
2. Replace every `LocalTime` type reference with `LocalMinuteTime`
3. Replace `LocalTime.of(7, 0)` with `LocalMinuteTime.of(7, 0)` and `LocalTime.of(20, 0)` with `LocalMinuteTime.of(20, 0)`

Then delete the original from `jvmMain`.

- [ ] **Step 3: Copy `FormattingPreferencesStore.kt` from `jvmMain` to `commonMain`**

Copy `core/domain/src/jvmMain/kotlin/com/riffle/core/domain/FormattingPreferencesStore.kt` to `core/domain/src/commonMain/kotlin/com/riffle/core/domain/FormattingPreferencesStore.kt`. The file contents are identical — no `LocalTime` usage. Delete the original from `jvmMain`.

- [ ] **Step 4: Update `FormattingPreferencesStoreImpl.kt` (androidMain)**

In `core/data/src/androidMain/kotlin/com/riffle/core/data/FormattingPreferencesStoreImpl.kt`:
- Remove `import java.time.LocalTime`
- Add `import com.riffle.core.domain.LocalMinuteTime`
- Any `LocalTime` reference used to serialize/deserialize becomes `LocalMinuteTime`. The serialization should store `hour` and `minute` as separate int fields (matching any existing proto/datastore schema). If using DataStore proto, map the existing `hour`/`minute` int fields; if storing as a string `"HH:mm"`, parse manually.

- [ ] **Step 5: Update `AppearanceCoordinatorImpl.kt` and `TimeProvider.kt`**

In `core/data/src/androidMain/kotlin/com/riffle/core/data/AppearanceCoordinatorImpl.kt`:
- Remove `import java.time.LocalTime`
- Add `import com.riffle.core.domain.LocalMinuteTime`
- Replace `LocalTime.now()` with a call to read the current clock: `val c = java.util.Calendar.getInstance(); LocalMinuteTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))`
- Replace all `LocalTime` type references with `LocalMinuteTime`

In `core/common/src/jvmMain/kotlin/com/riffle/core/common/TimeProvider.kt`:
- Same pattern — replace `LocalTime` with `LocalMinuteTime`

- [ ] **Step 6: Verify build compiles**

```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew :core:domain:jvmTest :core:data:compileDebugKotlin 2>&1 | tail -30
```

Expected: no `Unresolved reference: LocalTime` errors.

- [ ] **Step 7: Run existing formatting tests**

```bash
./gradlew :core:domain:jvmTest 2>&1 | tail -20
```

Expected: `FormattingPreferencesAutoScrollTest`, `FormattingPreferencesResolutionTest` pass. `ThemeSchedule` tests (if any) use `LocalMinuteTime.of(h, m)`.

- [ ] **Step 8: Commit**

```bash
git add core/domain/src/commonMain/kotlin/com/riffle/core/domain/LocalMinuteTime.kt \
        core/domain/src/commonMain/kotlin/com/riffle/core/domain/FormattingPreferences.kt \
        core/domain/src/commonMain/kotlin/com/riffle/core/domain/FormattingPreferencesStore.kt \
        core/data/src/androidMain/kotlin/com/riffle/core/data/FormattingPreferencesStoreImpl.kt \
        core/data/src/androidMain/kotlin/com/riffle/core/data/AppearanceCoordinatorImpl.kt \
        core/common/src/jvmMain/kotlin/com/riffle/core/common/TimeProvider.kt
git rm core/domain/src/jvmMain/kotlin/com/riffle/core/domain/FormattingPreferences.kt \
       core/domain/src/jvmMain/kotlin/com/riffle/core/domain/FormattingPreferencesStore.kt
git commit -m "$(cat <<'EOF'
refactor(domain): move FormattingPreferences + Store to commonMain

Replaces java.time.LocalTime with LocalMinuteTime so the formatting
preferences domain layer is reachable from iOS Compose Multiplatform.
EOF
)"
```

---

## Task 2: Move `AppThemeStore` to `commonMain`, add iOS no-op

**Files:**
- Create: `core/domain/src/commonMain/kotlin/com/riffle/core/domain/AppTheme.kt`
- Delete: `core/domain/src/jvmMain/kotlin/com/riffle/core/domain/AppTheme.kt`
- Modify: `shared/src/iosMain/kotlin/com/riffle/shared/library/IosNoOpImpls.kt`
- Modify: `shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt`

**Interfaces:**
- Produces: `AppThemeStore` in `commonMain` — used by Task 6

- [ ] **Step 1: Move `AppTheme.kt` to `commonMain`**

Copy `core/domain/src/jvmMain/kotlin/com/riffle/core/domain/AppTheme.kt` (the file is pure Kotlin, no JVM imports) to `core/domain/src/commonMain/kotlin/com/riffle/core/domain/AppTheme.kt`. Delete the original from `jvmMain`.

- [ ] **Step 2: Add `IosNoOpAppThemeStore` to `IosNoOpImpls.kt`**

```kotlin
// Append to shared/src/iosMain/kotlin/com/riffle/shared/library/IosNoOpImpls.kt
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore

internal class IosNoOpAppThemeStore : AppThemeStore {
    override val appTheme: Flow<AppTheme> = flowOf(AppTheme.System)
    override suspend fun setAppTheme(value: AppTheme) {}
}
```

- [ ] **Step 3: Register in `Koin.kt`**

In `shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt`, inside `iosLibraryModule {}`:

```kotlin
single<AppThemeStore> { IosNoOpAppThemeStore() }
```

- [ ] **Step 4: Verify**

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64 2>&1 | tail -20
```

Expected: compiles with no `Unresolved reference: AppThemeStore` errors.

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/commonMain/kotlin/com/riffle/core/domain/AppTheme.kt \
        shared/src/iosMain/kotlin/com/riffle/shared/library/IosNoOpImpls.kt \
        shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt
git rm core/domain/src/jvmMain/kotlin/com/riffle/core/domain/AppTheme.kt
git commit -m "$(cat <<'EOF'
refactor(domain): move AppThemeStore to commonMain; add iOS no-op
EOF
)"
```

---

## Task 3: Move `DownloadsRepository` to `commonMain`, add iOS stub impl

**Files:**
- Create: `core/domain/src/commonMain/kotlin/com/riffle/core/domain/DownloadsRepository.kt`
- Delete: `core/domain/src/jvmMain/kotlin/com/riffle/core/domain/DownloadsRepository.kt`
- Create: `shared/src/iosMain/kotlin/com/riffle/shared/downloads/IosDownloadsRepositoryImpl.kt`
- Modify: `shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt`

**Interfaces:**
- Produces: `DownloadsRepository` in `commonMain` — used by Task 5

- [ ] **Step 1: Move `DownloadsRepository.kt` to `commonMain`**

Copy `core/domain/src/jvmMain/kotlin/com/riffle/core/domain/DownloadsRepository.kt` to `core/domain/src/commonMain/kotlin/com/riffle/core/domain/DownloadsRepository.kt`. Inspect the file for any `java.*` imports — remove them if present. The interface uses only `List<StoredItemArtifact>`, `List<StoredItemRef>`, `Long`, `String`, and `suspend` — all KMP-compatible. Delete the original from `jvmMain`.

- [ ] **Step 2: Create `IosDownloadsRepositoryImpl`**

```kotlin
// shared/src/iosMain/kotlin/com/riffle/shared/downloads/IosDownloadsRepositoryImpl.kt
package com.riffle.shared.downloads

import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredItemRef

internal class IosDownloadsRepositoryImpl : DownloadsRepository {
    override fun getDownloadedArtifacts(): List<StoredItemArtifact> = emptyList()
    override fun getCachedArtifacts(): List<StoredItemArtifact> = emptyList()
    override fun sizeOf(sourceId: String, itemId: String): Long = 0L
    override suspend fun removeDownload(sourceId: String, itemId: String) {}
    override suspend fun removeCached(sourceId: String, itemId: String) {}
    override suspend fun removeAllDownloads() {}
    override suspend fun clearAllCached() {}
}
```

- [ ] **Step 3: Register in `Koin.kt`**

```kotlin
single<DownloadsRepository> { IosDownloadsRepositoryImpl() }
```

- [ ] **Step 4: Compile check**

```bash
./gradlew :core:domain:jvmTest :shared:compileKotlinIosSimulatorArm64 2>&1 | tail -20
```

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/commonMain/kotlin/com/riffle/core/domain/DownloadsRepository.kt \
        shared/src/iosMain/kotlin/com/riffle/shared/downloads/IosDownloadsRepositoryImpl.kt \
        shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt
git rm core/domain/src/jvmMain/kotlin/com/riffle/core/domain/DownloadsRepository.kt
git commit -m "$(cat <<'EOF'
refactor(domain): move DownloadsRepository to commonMain; add iOS stub impl
EOF
)"
```

---

## Task 4: Extend `HomeScreen` with top-level `AppSection` navigation + drawer links

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/HomeScreen.kt`

**Interfaces:**
- Produces: `AppSection` sealed type; `onNavigateToSettings: () -> Unit`, `onNavigateToDownloads: () -> Unit` callbacks for drawer — consumed by Tasks 5 and 7

- [ ] **Step 1: Write a failing test for drawer navigation**

```kotlin
// shared/src/commonTest/kotlin/com/riffle/shared/HomeScreenDrawerTest.kt
// (create file; use Compose UI test or a simple state-machine test)
// Test that AppSection toggles correctly when drawer items are clicked.
// Since Compose UI tests in commonTest require a runner, use a plain state test:
class AppSectionStateTest {
    @Test
    fun `drawer settings click sets section to Settings`() {
        var section: AppSection = AppSection.Library
        val onSettings = { section = AppSection.Settings }
        onSettings()
        assertEquals(AppSection.Settings, section)
    }

    @Test
    fun `drawer downloads click sets section to Downloads`() {
        var section: AppSection = AppSection.Library
        val onDownloads = { section = AppSection.Downloads }
        onDownloads()
        assertEquals(AppSection.Downloads, section)
    }
}
```

Place in `shared/src/commonTest/kotlin/com/riffle/shared/AppSectionStateTest.kt`.

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :shared:jvmTest --tests "com.riffle.shared.AppSectionStateTest" 2>&1 | tail -10
```

Expected: FAIL — `AppSection` not yet defined.

- [ ] **Step 3: Add `AppSection` and wire `HomeScreen`**

In `shared/src/commonMain/kotlin/com/riffle/shared/HomeScreen.kt`:

1. Add below the imports, before `HomeScreen()`:

```kotlin
sealed interface AppSection {
    data object Library : AppSection
    data object Settings : AppSection
    data object Downloads : AppSection
}
```

2. In `HomeScreen()`, replace the top-level `Box` with:

```kotlin
@Composable
fun HomeScreen() {
    val drawerViewModel = koinInject<DrawerViewModel>()
    var drawerOpen by remember { mutableStateOf(false) }
    var appSection by rememberSaveable { mutableStateOf<AppSection>(AppSection.Library) }
    // ... existing allServers, activeServer, visibleLibraries, activeLibraryId collection ...

    Box(Modifier.fillMaxSize()) {
        when (appSection) {
            AppSection.Library -> LibraryHost(
                /* existing params */
                onOpenDrawer = { drawerOpen = true },
            )
            AppSection.Settings -> SettingsScreen(
                onBack = { appSection = AppSection.Library }
            )
            AppSection.Downloads -> DownloadsScreen(
                onBack = { appSection = AppSection.Library }
            )
        }

        if (drawerOpen) {
            // scrim (unchanged)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { drawerOpen = false })
            // drawer panel
            Box(Modifier.fillMaxHeight().width(280.dp).background(Color.White).align(Alignment.TopStart)) {
                DrawerSheetContent(
                    activeServer = activeServer,
                    allServers = allServers,
                    visibleLibraries = visibleLibraries,
                    activeLibraryId = activeLibraryId,
                    onServerSelected = { source ->
                        drawerOpen = false
                        drawerViewModel.setActiveServer(source.id)
                    },
                    onLibrarySelected = { library ->
                        drawerOpen = false
                        activeLibraryId = library.id
                        drawerViewModel.setActiveLibrary(library.id)
                        destination = HomeViewModel.StartDestination.Library(/* ... */)
                    },
                    onSettingsSelected = {
                        drawerOpen = false
                        appSection = AppSection.Settings
                    },
                    onDownloadsSelected = {
                        drawerOpen = false
                        appSection = AppSection.Downloads
                    },
                )
            }
        }
    }
}
```

3. Update `DrawerSheetContent` signature and body:

```kotlin
@Composable
private fun DrawerSheetContent(
    activeServer: Source?,
    allServers: List<Source>,
    visibleLibraries: List<Library>,
    activeLibraryId: String?,
    onServerSelected: (Source) -> Unit,
    onLibrarySelected: (Library) -> Unit,
    onSettingsSelected: () -> Unit,
    onDownloadsSelected: () -> Unit,
) {
    Column(Modifier.fillMaxHeight()) {
        // ... existing server header and library list (unchanged) ...

        // Bottom actions pinned below the library list
        Spacer(Modifier.weight(1f)) // pushes these to the bottom if library list is short
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onDownloadsSelected).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Use a simple unicode character or Material icon placeholder until icons task
            BasicText("⬇  Downloads", style = TextStyle(fontSize = 15.sp))
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSettingsSelected).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("⚙  Settings", style = TextStyle(fontSize = 15.sp))
        }
    }
}
```

Note: `SettingsScreen` and `DownloadsScreen` do not exist yet — add stub `@Composable fun SettingsScreen(onBack: () -> Unit) {}` and `@Composable fun DownloadsScreen(onBack: () -> Unit) {}` in new files to unblock compilation.

- [ ] **Step 4: Run the state test**

```bash
./gradlew :shared:jvmTest --tests "com.riffle.shared.AppSectionStateTest" 2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 5: Compile check**

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64 2>&1 | tail -20
```

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/riffle/shared/HomeScreen.kt \
        shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/SettingsScreen.kt \
        shared/src/commonMain/kotlin/com/riffle/shared/feature/downloads/DownloadsScreen.kt \
        shared/src/commonTest/kotlin/com/riffle/shared/AppSectionStateTest.kt
git commit -m "$(cat <<'EOF'
feat(ios): add AppSection top-level nav and Settings/Downloads drawer links
EOF
)"
```

---

## Task 5: `DownloadsViewModel` + `DownloadsScreen`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/riffle/shared/feature/downloads/DownloadsViewModel.kt`
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/feature/downloads/DownloadsScreen.kt` (replace stub)
- Modify: `shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt`
- Test: `shared/src/commonTest/kotlin/com/riffle/shared/feature/downloads/DownloadsViewModelTest.kt`

**Interfaces:**
- Consumes: `DownloadsRepository` (Task 3), `ContentCacheSettingsStore` (already commonMain), `LibraryObserver` (already registered in Koin)
- Produces: `DownloadsViewModel` — instantiated by Koin via `koinInject<DownloadsViewModel>()` in `DownloadsScreen`

- [ ] **Step 1: Write failing ViewModel test**

```kotlin
// shared/src/commonTest/kotlin/com/riffle/shared/feature/downloads/DownloadsViewModelTest.kt
package com.riffle.shared.feature.downloads

import app.cash.turbine.test
import com.riffle.core.domain.ContentCacheAutoClear
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredMediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadsViewModelTest {
    private val fakeDownloadsRepository = object : DownloadsRepository {
        override fun getDownloadedArtifacts() = listOf(
            StoredItemArtifact(sourceId = "s1", itemId = "i1", mediaType = StoredMediaType.Epub)
        )
        override fun getCachedArtifacts() = emptyList<StoredItemArtifact>()
        override fun sizeOf(sourceId: String, itemId: String) = 1024L
        override suspend fun removeDownload(sourceId: String, itemId: String) {}
        override suspend fun removeCached(sourceId: String, itemId: String) {}
        override suspend fun removeAllDownloads() {}
        override suspend fun clearAllCached() {}
    }
    private val fakeCacheStore = object : ContentCacheSettingsStore {
        override val autoClear = MutableStateFlow(ContentCacheAutoClear.After30Days)
        override suspend fun setAutoClear(value: ContentCacheAutoClear) {}
    }

    @Test
    fun `uiState exposes downloaded item count`() = runTest {
        val vm = DownloadsViewModel(fakeDownloadsRepository, fakeCacheStore)
        vm.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.downloadedItems.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `downloaded total bytes sums artifact sizes`() = runTest {
        val vm = DownloadsViewModel(fakeDownloadsRepository, fakeCacheStore)
        vm.uiState.test {
            val state = awaitItem()
            assertEquals(1024L, state.downloadedTotalBytes)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :shared:jvmTest --tests "com.riffle.shared.feature.downloads.DownloadsViewModelTest" 2>&1 | tail -10
```

Expected: FAIL — `DownloadsViewModel` not found.

- [ ] **Step 3: Implement `DownloadsViewModel`**

```kotlin
// shared/src/commonMain/kotlin/com/riffle/shared/feature/downloads/DownloadsViewModel.kt
package com.riffle.shared.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.ContentCacheAutoClear
import com.riffle.core.domain.ContentCacheSettingsStore
import com.riffle.core.domain.DownloadsRepository
import com.riffle.core.domain.StoredItemArtifact
import com.riffle.core.domain.StoredMediaType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LocalItemUi(
    val sourceId: String,
    val itemId: String,
    val mediaTypes: List<StoredMediaType>,
    val sizeBytes: Long,
)

data class DownloadsUiState(
    val downloadedItems: List<LocalItemUi> = emptyList(),
    val cachedItems: List<LocalItemUi> = emptyList(),
    val cacheAutoClear: ContentCacheAutoClear = ContentCacheAutoClear.After30Days,
) {
    val downloadedTotalBytes: Long get() = downloadedItems.sumOf { it.sizeBytes }
    val cachedTotalBytes: Long get() = cachedItems.sumOf { it.sizeBytes }
}

class DownloadsViewModel(
    private val downloadsRepository: DownloadsRepository,
    private val cacheSettingsStore: ContentCacheSettingsStore,
) : ViewModel() {

    val uiState: StateFlow<DownloadsUiState> = combine(
        // Repository calls are synchronous; wrap in a flow for reactivity
        kotlinx.coroutines.flow.flow { emit(downloadsRepository.getDownloadedArtifacts()) },
        kotlinx.coroutines.flow.flow { emit(downloadsRepository.getCachedArtifacts()) },
        cacheSettingsStore.autoClear,
    ) { downloaded, cached, autoClear ->
        DownloadsUiState(
            downloadedItems = downloaded.toUiItems(),
            cachedItems = cached.toUiItems(),
            cacheAutoClear = autoClear,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun removeDownload(sourceId: String, itemId: String) {
        viewModelScope.launch { downloadsRepository.removeDownload(sourceId, itemId) }
    }

    fun removeCached(sourceId: String, itemId: String) {
        viewModelScope.launch { downloadsRepository.removeCached(sourceId, itemId) }
    }

    fun removeAllDownloads() {
        viewModelScope.launch { downloadsRepository.removeAllDownloads() }
    }

    fun clearAllCached() {
        viewModelScope.launch { downloadsRepository.clearAllCached() }
    }

    fun setCacheAutoClear(value: ContentCacheAutoClear) {
        viewModelScope.launch { cacheSettingsStore.setAutoClear(value) }
    }

    private fun List<StoredItemArtifact>.toUiItems(): List<LocalItemUi> =
        groupBy { it.sourceId to it.itemId }
            .map { (key, artifacts) ->
                val (sourceId, itemId) = key
                LocalItemUi(
                    sourceId = sourceId,
                    itemId = itemId,
                    mediaTypes = artifacts.map { it.mediaType },
                    sizeBytes = downloadsRepository.sizeOf(sourceId, itemId),
                )
            }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :shared:jvmTest --tests "com.riffle.shared.feature.downloads.DownloadsViewModelTest" 2>&1 | tail -10
```

Expected: PASS.

- [ ] **Step 5: Implement `DownloadsScreen`**

Replace the stub `DownloadsScreen.kt` with a real implementation:

```kotlin
// shared/src/commonMain/kotlin/com/riffle/shared/feature/downloads/DownloadsScreen.kt
package com.riffle.shared.feature.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.ContentCacheAutoClear
import org.koin.compose.koinInject

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val viewModel = koinInject<DownloadsViewModel>()
    val state by viewModel.uiState.collectAsState()
    var showRemoveAllDialog by remember { mutableStateOf(false) }
    var showClearCachedDialog by remember { mutableStateOf(false) }
    var showCacheSettingsDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            // Downloaded section header
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Downloaded · ${formatBytes(state.downloadedTotalBytes)}", style = MaterialTheme.typography.titleSmall)
                    if (state.downloadedItems.isNotEmpty()) {
                        TextButton(onClick = { showRemoveAllDialog = true }) { Text("Remove all") }
                    }
                }
            }
            if (state.downloadedItems.isEmpty()) {
                item { Text("No downloaded items", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.downloadedItems, key = { "${it.sourceId}/${it.itemId}" }) { item ->
                DownloadItemRow(item = item, onDelete = { viewModel.removeDownload(item.sourceId, item.itemId) })
            }

            // Cached section header
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Cached · ${formatBytes(state.cachedTotalBytes)}", style = MaterialTheme.typography.titleSmall)
                    if (state.cachedItems.isNotEmpty()) {
                        TextButton(onClick = { showClearCachedDialog = true }) { Text("Clear all") }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { showCacheSettingsDialog = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text("Cache settings: ${state.cacheAutoClear.label}")
                }
            }
            items(state.cachedItems, key = { "cached_${it.sourceId}/${it.itemId}" }) { item ->
                DownloadItemRow(item = item, onDelete = { viewModel.removeCached(item.sourceId, item.itemId) })
            }
        }
    }

    if (showRemoveAllDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveAllDialog = false },
            title = { Text("Remove all downloads?") },
            text = { Text("This will delete all downloaded content from your device.") },
            confirmButton = { TextButton(onClick = { viewModel.removeAllDownloads(); showRemoveAllDialog = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { showRemoveAllDialog = false }) { Text("Cancel") } },
        )
    }

    if (showClearCachedDialog) {
        AlertDialog(
            onDismissRequest = { showClearCachedDialog = false },
            title = { Text("Clear cache?") },
            text = { Text("Streamed content will need to be re-downloaded.") },
            confirmButton = { TextButton(onClick = { viewModel.clearAllCached(); showClearCachedDialog = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { showClearCachedDialog = false }) { Text("Cancel") } },
        )
    }

    if (showCacheSettingsDialog) {
        CacheSettingsDialog(
            current = state.cacheAutoClear,
            onSelect = { viewModel.setCacheAutoClear(it); showCacheSettingsDialog = false },
            onDismiss = { showCacheSettingsDialog = false },
        )
    }
}

@Composable
private fun DownloadItemRow(item: LocalItemUi, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${item.sourceId} / ${item.itemId}", style = MaterialTheme.typography.bodyMedium)
            Text(item.mediaTypes.joinToString(" + ") { it.name }, style = MaterialTheme.typography.bodySmall)
            Text(formatBytes(item.sizeBytes), style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onDelete) { Text("🗑") }
    }
}

@Composable
private fun CacheSettingsDialog(
    current: ContentCacheAutoClear,
    onSelect: (ContentCacheAutoClear) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-clear cache") },
        text = {
            Column {
                ContentCacheAutoClear.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = option == current, onClick = { onSelect(option) })
                        Text(option.label, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

private val ContentCacheAutoClear.label: String
    get() = when (this) {
        ContentCacheAutoClear.Off -> "Off"
        ContentCacheAutoClear.After7Days -> "After 7 days"
        ContentCacheAutoClear.After30Days -> "After 30 days"
        ContentCacheAutoClear.After90Days -> "After 90 days"
    }
```

- [ ] **Step 6: Register `DownloadsViewModel` in `Koin.kt`**

```kotlin
// In iosLibraryModule inside Koin.kt:
single { DownloadsViewModel(get(), get()) }
```

- [ ] **Step 7: Compile + run tests**

```bash
./gradlew :shared:jvmTest :shared:compileKotlinIosSimulatorArm64 2>&1 | tail -20
```

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/com/riffle/shared/feature/downloads/ \
        shared/src/commonTest/kotlin/com/riffle/shared/feature/downloads/ \
        shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt
git commit -m "$(cat <<'EOF'
feat(ios): implement DownloadsViewModel and DownloadsScreen
EOF
)"
```

---

## Task 6: `SettingsViewModel` in `commonMain`

**Files:**
- Create: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/SettingsViewModel.kt`
- Modify: `shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt`
- Test: `shared/src/commonTest/kotlin/com/riffle/shared/feature/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `SourceRepository`, `FormattingPreferencesStore` (Task 1), `AppThemeStore` (Task 2), `ListeningPreferencesStore`, `ReadaloudPreferencesStore`, `AnnotationSyncConfigStore`, `ContentCacheSettingsStore`
- Produces: `SettingsViewModel` with `SettingsUiState` — used by Tasks 7–14

- [ ] **Step 1: Write failing test**

```kotlin
// shared/src/commonTest/kotlin/com/riffle/shared/feature/settings/SettingsViewModelTest.kt
package com.riffle.shared.feature.settings

import app.cash.turbine.test
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsViewModelTest {
    private val fakeAppThemeStore = object : AppThemeStore {
        val _theme = MutableStateFlow(AppTheme.System)
        override val appTheme = _theme
        override suspend fun setAppTheme(value: AppTheme) { _theme.value = value }
    }

    @Test
    fun `setAppTheme updates state`() = runTest {
        val vm = SettingsViewModel(
            sourceRepository = FakeSourceRepository(),
            formattingPreferencesStore = FakeFormattingPreferencesStore(),
            appThemeStore = fakeAppThemeStore,
            listeningPreferencesStore = FakeListeningPreferencesStore(),
            readaloudPreferencesStore = FakeReadaloudPreferencesStore(),
            annotationSyncConfigStore = FakeAnnotationSyncConfigStore(),
        )
        vm.setAppTheme(AppTheme.Dark)
        assertEquals(AppTheme.Dark, fakeAppThemeStore._theme.value)
    }
}
```

Note: `FakeSourceRepository`, `FakeFormattingPreferencesStore`, etc. are inner objects matching their respective interfaces with default/noop implementations. Create them in the test file.

- [ ] **Step 2: Run to verify failure**

```bash
./gradlew :shared:jvmTest --tests "com.riffle.shared.feature.settings.SettingsViewModelTest" 2>&1 | tail -10
```

Expected: FAIL.

- [ ] **Step 3: Implement `SettingsViewModel`**

```kotlin
// shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/SettingsViewModel.kt
package com.riffle.shared.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AppThemeStore
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.models.HighlightColor
import com.riffle.core.sources.SourceRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val appTheme: AppTheme = AppTheme.System,
    val formattingPreferences: FormattingPreferences = FormattingPreferences(),
    val defaultPlaybackSpeed: Float = 1.0f,
    val skipIntervalSeconds: Int = 30,
    val rewindIntervalSeconds: Int = 15,
    val rewindOnResumeSeconds: Int = 0,
    val readaloudHighlightColor: HighlightColor = HighlightColor.Yellow,
    val annotationSyncConfigured: Boolean = false,
)

class SettingsViewModel(
    private val sourceRepository: SourceRepository,
    private val formattingPreferencesStore: FormattingPreferencesStore,
    private val appThemeStore: AppThemeStore,
    private val listeningPreferencesStore: ListeningPreferencesStore,
    private val readaloudPreferencesStore: ReadaloudPreferencesStore,
    private val annotationSyncConfigStore: AnnotationSyncConfigStore,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        appThemeStore.appTheme,
        formattingPreferencesStore.preferences,
        listeningPreferencesStore.defaultPlaybackSpeed,
        listeningPreferencesStore.skipIntervalSeconds,
        listeningPreferencesStore.rewindIntervalSeconds,
    ) { theme, prefs, speed, skip, rewind ->
        SettingsUiState(
            appTheme = theme,
            formattingPreferences = prefs,
            defaultPlaybackSpeed = speed,
            skipIntervalSeconds = skip,
            rewindIntervalSeconds = rewind,
        )
    }.combine(listeningPreferencesStore.rewindOnResumeSeconds) { state, rewindOnResume ->
        state.copy(rewindOnResumeSeconds = rewindOnResume)
    }.combine(readaloudPreferencesStore.highlightColor) { state, color ->
        state.copy(readaloudHighlightColor = color)
    }.combine(annotationSyncConfigStore.observe()) { state, config ->
        state.copy(annotationSyncConfigured = config != null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setAppTheme(theme: AppTheme) { viewModelScope.launch { appThemeStore.setAppTheme(theme) } }
    fun updateFormattingPreferences(prefs: FormattingPreferences) { viewModelScope.launch { formattingPreferencesStore.update(prefs) } }
    fun setDefaultPlaybackSpeed(speed: Float) { viewModelScope.launch { listeningPreferencesStore.setDefaultPlaybackSpeed(speed) } }
    fun setSkipInterval(seconds: Int) { viewModelScope.launch { listeningPreferencesStore.setSkipIntervalSeconds(seconds) } }
    fun setRewindInterval(seconds: Int) { viewModelScope.launch { listeningPreferencesStore.setRewindIntervalSeconds(seconds) } }
    fun setRewindOnResume(seconds: Int) { viewModelScope.launch { listeningPreferencesStore.setRewindOnResumeSeconds(seconds) } }
    fun setReadaloudHighlightColor(color: HighlightColor) { viewModelScope.launch { readaloudPreferencesStore.setHighlightColor(color) } }
}
```

- [ ] **Step 4: Register in Koin.kt**

```kotlin
single {
    SettingsViewModel(
        sourceRepository = get(),
        formattingPreferencesStore = get(),
        appThemeStore = get(),
        listeningPreferencesStore = get(),
        readaloudPreferencesStore = get(),
        annotationSyncConfigStore = get(),
    )
}
```

Also add iOS no-op stubs for any store that doesn't yet have one in `Koin.kt` (check: `ReadaloudPreferencesStore` — it's in `commonMain`, verify its iOS impl is registered; `AnnotationSyncConfigStore` — check if iOS has an impl).

- [ ] **Step 5: Add `IosNoOpFormattingPreferencesStore` to `IosNoOpImpls.kt`**

```kotlin
internal class IosNoOpFormattingPreferencesStore : FormattingPreferencesStore {
    override val preferences: Flow<FormattingPreferences> = flowOf(FormattingPreferences())
    override suspend fun update(preferences: FormattingPreferences) {}
    override suspend fun setCadencePlatformSupported(supported: Boolean) {}
}
```

Register: `single<FormattingPreferencesStore> { IosNoOpFormattingPreferencesStore() }`

- [ ] **Step 6: Run tests + compile**

```bash
./gradlew :shared:jvmTest --tests "com.riffle.shared.feature.settings.SettingsViewModelTest" 2>&1 | tail -10
./gradlew :shared:compileKotlinIosSimulatorArm64 2>&1 | tail -20
```

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/SettingsViewModel.kt \
        shared/src/commonTest/kotlin/com/riffle/shared/feature/settings/SettingsViewModelTest.kt \
        shared/src/iosMain/kotlin/com/riffle/shared/library/IosNoOpImpls.kt \
        shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt
git commit -m "$(cat <<'EOF'
feat(ios): add shared SettingsViewModel with commonMain stores
EOF
)"
```

---

## Task 7: `SettingsScreen` scaffold + `SettingsNav` internal routing

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `SettingsViewModel` (Task 6)
- Produces: `SettingsScreen(onBack)` — routing to sub-screens via internal `SettingsNav`

- [ ] **Step 1: Define `SettingsNav` and scaffold**

Replace the stub `SettingsScreen.kt`:

```kotlin
// shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/SettingsScreen.kt
package com.riffle.shared.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import org.koin.compose.koinInject

private sealed interface SettingsNav {
    data object Root : SettingsNav
    data object ReadaloudSettings : SettingsNav
    data object AnnotationsSyncSettings : SettingsNav
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel = koinInject<SettingsViewModel>()
    val state by viewModel.uiState.collectAsState()
    var settingsNav by rememberSaveable { mutableStateOf<SettingsNav>(SettingsNav.Root) }
    var openPanel by remember { mutableStateOf<SettingsPanel?>(null) }

    when (val nav = settingsNav) {
        SettingsNav.Root -> SettingsRootScreen(
            state = state,
            viewModel = viewModel,
            onBack = onBack,
            onOpenPanel = { openPanel = it },
            onNavigateToReadaloud = { settingsNav = SettingsNav.ReadaloudSettings },
            onNavigateToAnnotationsSync = { settingsNav = SettingsNav.AnnotationsSyncSettings },
        )
        SettingsNav.ReadaloudSettings -> ReadaloudSettingsScreen(
            state = state,
            viewModel = viewModel,
            onBack = { settingsNav = SettingsNav.Root },
        )
        SettingsNav.AnnotationsSyncSettings -> AnnotationsSyncSettingsScreen(
            onBack = { settingsNav = SettingsNav.Root },
        )
    }

    openPanel?.let { panel ->
        SettingsPanelHost(
            panel = panel,
            state = state,
            viewModel = viewModel,
            onDismiss = { openPanel = null },
        )
    }
}

enum class SettingsPanel { Formatting, Display, AutoScroll, Cadence, Listening, ComicDisplay }
```

- [ ] **Step 2: Add `SettingsRootScreen` (the scrollable section list)**

```kotlin
@Composable
private fun SettingsRootScreen(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenPanel: (SettingsPanel) -> Unit,
    onNavigateToReadaloud: () -> Unit,
    onNavigateToAnnotationsSync: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←") } },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SourcesSection(viewModel = viewModel)
            SettingsDivider()
            AppearanceSection(state = state, viewModel = viewModel)
            SettingsDivider()
            ReadingSection(onOpenPanel = onOpenPanel)
            SettingsDivider()
            ComicsSection(onOpenPanel = onOpenPanel)
            SettingsDivider()
            ListeningSection(onOpenPanel = onOpenPanel)
            SettingsDivider()
            ReadaloudSection(onNavigate = onNavigateToReadaloud)
            SettingsDivider()
            AnnotationsSyncSection(configured = state.annotationSyncConfigured, onNavigate = onNavigateToAnnotationsSync)
            SettingsDivider()
            AppVersionSection()
            PlatformSettingsSections(viewModel = viewModel)
        }
    }
}

@Composable
private fun SettingsDivider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp))
```

- [ ] **Step 3: Compile check (stubs for section composables are enough)**

Temporarily add empty stub composables for `SourcesSection`, `AppearanceSection`, etc., in their respective files so the screen compiles. They will be replaced in Tasks 8–14.

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64 2>&1 | tail -20
```

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/
git commit -m "$(cat <<'EOF'
feat(ios): scaffold SettingsScreen with SettingsNav internal routing
EOF
)"
```

---

## Task 8: Sources section

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/SourcesSection.kt`

- [ ] **Step 1: Write a test for source display**

```kotlin
// shared/src/commonTest/kotlin/com/riffle/shared/feature/settings/SourcesSectionTest.kt
// Test the ViewModel sources state rather than the Composable directly.
// The SettingsViewModel already covers source observation via SourceRepository.
// Verify via SettingsViewModelTest: add a test that a source in the repository
// appears in state (sources is not yet in SettingsUiState — add it).
```

Add `sources: List<Source>` to `SettingsUiState` and combine `sourceRepository.observeAll()` in `SettingsViewModel.uiState`. Write the test asserting that a seeded source appears in `state.sources`.

- [ ] **Step 2: Implement `SourcesSection`**

```kotlin
// shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/SourcesSection.kt
package com.riffle.shared.feature.settings.sections

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.models.Source
import com.riffle.shared.feature.settings.SettingsViewModel

@Composable
fun SourcesSection(viewModel: SettingsViewModel) {
    // The section uses state.sources from SettingsUiState.
    // An "Add source" button navigates to AddAbsSourceScreen (future task;
    // for now it is a no-op button).
    Column {
        SettingsSectionHeader("Sources")
        viewModel.uiState.collectAsState().value.sources.forEach { source ->
            SourceRow(source = source, onRemove = { viewModel.removeSource(source.id) })
        }
        OutlinedButton(
            onClick = { /* TODO: navigate to add source */ },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Add source")
        }
    }
}

@Composable
internal fun SourceRow(source: Source, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.serverType.label, style = MaterialTheme.typography.bodyMedium)
            Text(source.url.authority(), style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onRemove) { Text("✕") }
    }
}

@Composable
internal fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
```

Also add `fun removeSource(sourceId: String)` to `SettingsViewModel` calling `sourceRepository.remove(sourceId)`.

- [ ] **Step 3: Run tests + compile**

```bash
./gradlew :shared:jvmTest :shared:compileKotlinIosSimulatorArm64 2>&1 | tail -20
```

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/SourcesSection.kt
git commit -m "$(cat <<'EOF'
feat(ios): implement Sources settings section
EOF
)"
```

---

## Task 9: Appearance section

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/AppearanceSection.kt`

- [ ] **Step 1: Implement `AppearanceSection`**

```kotlin
@Composable
fun AppearanceSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column {
        SettingsSectionHeader("Appearance")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Theme", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            // Segmented button: Light / Dark / System
            AppTheme.entries.forEach { theme ->
                FilterChip(
                    selected = state.appTheme == theme,
                    onClick = { viewModel.setAppTheme(theme) },
                    label = { Text(theme.name) },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 2: Compile check + commit**

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64 2>&1 | tail -10
git add shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/AppearanceSection.kt
git commit -m "$(cat <<'EOF'
feat(ios): implement Appearance settings section
EOF
)"
```

---

## Task 10: Reading section + formatting/display/auto-scroll/cadence panels

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/ReadingSection.kt`
- Create: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/panels/FormattingSettingsPanel.kt`
- Create: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/panels/DisplaySettingsPanel.kt`
- Create: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/panels/AutoScrollSettingsPanel.kt`
- Create: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/panels/CadenceSettingsPanel.kt`

- [ ] **Step 1: Implement `ReadingSection` (row launchers)**

```kotlin
@Composable
fun ReadingSection(onOpenPanel: (SettingsPanel) -> Unit) {
    Column {
        SettingsSectionHeader("Reading")
        SettingsNavRow("Formatting") { onOpenPanel(SettingsPanel.Formatting) }
        SettingsNavRow("Display") { onOpenPanel(SettingsPanel.Display) }
        SettingsNavRow("Auto-scroll") { onOpenPanel(SettingsPanel.AutoScroll) }
        SettingsNavRow("Cadence") { onOpenPanel(SettingsPanel.Cadence) }
    }
}

@Composable
internal fun SettingsNavRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text("›", style = MaterialTheme.typography.bodyLarge)
    }
}
```

- [ ] **Step 2: Implement `SettingsPanelHost` in `SettingsScreen.kt`**

```kotlin
@Composable
private fun SettingsPanelHost(
    panel: SettingsPanel,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        when (panel) {
            SettingsPanel.Formatting -> FormattingSettingsPanel(state = state, viewModel = viewModel)
            SettingsPanel.Display -> DisplaySettingsPanel(state = state, viewModel = viewModel)
            SettingsPanel.AutoScroll -> AutoScrollSettingsPanel(state = state, viewModel = viewModel)
            SettingsPanel.Cadence -> CadenceSettingsPanel(state = state, viewModel = viewModel)
            SettingsPanel.Listening -> ListeningPreferencesPanel(state = state, viewModel = viewModel)
            SettingsPanel.ComicDisplay -> ComicDisplaySettingsPanel()
        }
    }
}
```

- [ ] **Step 3: Implement `FormattingSettingsPanel`**

Mirror the Android `FormattingSettingsPanel.kt`. Key controls:
- Font size slider (`preferences.fontSize`, range 0.7–2.0, step 0.05)
- Font family radio buttons (`ReaderFontFamily.entries`)
- Line spacing slider (`lineSpacing`, range 1.0–2.0)
- Margins slider (`margins`, range 0.5–2.0)
- Justify text switch

```kotlin
@Composable
fun FormattingSettingsPanel(state: SettingsUiState, viewModel: SettingsViewModel) {
    val prefs = state.formattingPreferences
    Column(Modifier.padding(16.dp)) {
        Text("Formatting", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        LabeledSlider("Font size", prefs.fontSize, 0.7f..2.0f, steps = 26) {
            viewModel.updateFormattingPreferences(prefs.copy(fontSize = it))
        }
        LabeledSlider("Line spacing", prefs.lineSpacing, 1.0f..2.0f, steps = 20) {
            viewModel.updateFormattingPreferences(prefs.copy(lineSpacing = it))
        }
        LabeledSlider("Margins", prefs.margins, 0.5f..2.0f, steps = 15) {
            viewModel.updateFormattingPreferences(prefs.copy(margins = it))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Justify text", Modifier.weight(1f))
            Switch(checked = prefs.justifyText, onCheckedChange = {
                viewModel.updateFormattingPreferences(prefs.copy(justifyText = it))
            })
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onValue: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onValue, valueRange = range, steps = steps)
    }
}
```

- [ ] **Step 4: Implement `DisplaySettingsPanel`, `AutoScrollSettingsPanel`, `CadenceSettingsPanel`**

Mirror Android panels. `DisplaySettingsPanel` covers scroll mode (`ReaderOrientation`) and reader theme (`ReaderTheme`). `AutoScrollSettingsPanel` covers `autoScrollWpm`. `CadenceSettingsPanel` covers `cadenceWpm` and `cadenceHighlightColor`.

Use the same `LabeledSlider` and radio/chip pattern. Call `viewModel.updateFormattingPreferences(prefs.copy(...))` for each change.

- [ ] **Step 5: Compile check**

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64 2>&1 | tail -20
```

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/ReadingSection.kt \
        shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/panels/
git commit -m "$(cat <<'EOF'
feat(ios): implement Reading section and formatting/display/autoscroll/cadence panels
EOF
)"
```

---

## Task 11: Listening section + panel

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/ListeningSection.kt`
- Create: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/panels/ListeningPreferencesPanel.kt`

- [ ] **Step 1: Implement `ListeningSection`**

```kotlin
@Composable
fun ListeningSection(onOpenPanel: (SettingsPanel) -> Unit) {
    Column {
        SettingsSectionHeader("Listening")
        SettingsNavRow("Listening preferences") { onOpenPanel(SettingsPanel.Listening) }
    }
}
```

- [ ] **Step 2: Implement `ListeningPreferencesPanel`**

```kotlin
@Composable
fun ListeningPreferencesPanel(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(Modifier.padding(16.dp)) {
        Text("Listening", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        LabeledSlider("Default speed", state.defaultPlaybackSpeed, 0.5f..3.0f, steps = 25) {
            viewModel.setDefaultPlaybackSpeed(it)
        }
        SettingsStepRow("Skip forward", "${state.skipIntervalSeconds}s",
            onDecrement = { viewModel.setSkipInterval((state.skipIntervalSeconds - 5).coerceAtLeast(5)) },
            onIncrement = { viewModel.setSkipInterval((state.skipIntervalSeconds + 5).coerceAtMost(120)) },
        )
        SettingsStepRow("Rewind", "${state.rewindIntervalSeconds}s",
            onDecrement = { viewModel.setRewindInterval((state.rewindIntervalSeconds - 5).coerceAtLeast(5)) },
            onIncrement = { viewModel.setRewindInterval((state.rewindIntervalSeconds + 5).coerceAtMost(120)) },
        )
        SettingsStepRow("Rewind on resume", "${state.rewindOnResumeSeconds}s",
            onDecrement = { viewModel.setRewindOnResume((state.rewindOnResumeSeconds - 5).coerceAtLeast(0)) },
            onIncrement = { viewModel.setRewindOnResume((state.rewindOnResumeSeconds + 5).coerceAtMost(60)) },
        )
    }
}

@Composable
private fun SettingsStepRow(label: String, value: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        IconButton(onClick = onDecrement) { Text("−") }
        Text(value, Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onIncrement) { Text("+") }
    }
}
```

- [ ] **Step 3: Compile + commit**

```bash
./gradlew :shared:compileKotlinIosSimulatorArm64 2>&1 | tail -10
git add shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/ListeningSection.kt \
        shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/panels/ListeningPreferencesPanel.kt
git commit -m "$(cat <<'EOF'
feat(ios): implement Listening section and preferences panel
EOF
)"
```

---

## Task 12: Comics section + panel

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/ComicsSection.kt`
- Create: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/panels/ComicDisplaySettingsPanel.kt`

- [ ] **Step 1: Implement `ComicsSection` + `ComicDisplaySettingsPanel`**

`ComicsSection` is a single row:

```kotlin
@Composable
fun ComicsSection(onOpenPanel: (SettingsPanel) -> Unit) {
    Column {
        SettingsSectionHeader("Comics")
        SettingsNavRow("Comic display") { onOpenPanel(SettingsPanel.ComicDisplay) }
    }
}
```

Add `ComicFormattingPreferencesStore` to `SettingsViewModel` constructor and `SettingsUiState`:

```kotlin
// In SettingsUiState:
val comicReadingDirection: ComicReadingDirection = ComicReadingDirection.LeftToRight

// In SettingsViewModel — add to combine chain:
comicFormattingPreferencesStore.preferences  // Flow<ComicFormattingPreferences>
// expose setter:
fun setComicReadingDirection(dir: ComicReadingDirection) { ... }
```

`ComicDisplaySettingsPanel` shows reading direction radio buttons:

```kotlin
@Composable
fun ComicDisplaySettingsPanel(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(Modifier.padding(16.dp)) {
        Text("Comic display", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text("Reading direction", style = MaterialTheme.typography.bodySmall)
        ComicReadingDirection.entries.forEach { dir ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = state.comicReadingDirection == dir, onClick = { viewModel.setComicReadingDirection(dir) })
                Text(dir.name, Modifier.padding(start = 8.dp))
            }
        }
    }
}
```

Add iOS no-op `ComicFormattingPreferencesStore` to `IosNoOpImpls.kt` if not already registered (check `Koin.kt` first — it may already exist).

- [ ] **Step 2: Compile + commit**

```bash
git add shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/ComicsSection.kt \
        shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/panels/ComicDisplaySettingsPanel.kt
git commit -m "$(cat <<'EOF'
feat(ios): implement Comics section and display panel
EOF
)"
```

---

## Task 13: Readaloud section + `ReadaloudSettingsScreen`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/ReadaloudSection.kt`
- Create: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/readaloud/ReadaloudSettingsScreen.kt`

- [ ] **Step 1: Implement `ReadaloudSection`**

```kotlin
@Composable
fun ReadaloudSection(onNavigate: () -> Unit) {
    Column {
        SettingsSectionHeader("Readaloud")
        SettingsNavRow("Readaloud settings", onClick = onNavigate)
    }
}
```

- [ ] **Step 2: Implement `ReadaloudSettingsScreen`**

Shows:
- Highlight color picker (chips for each `HighlightColor` entry)
- List of configured Storyteller sources (from `state.sources` filtered by `isStorytellerService`)
- Back button

```kotlin
@Composable
fun ReadaloudSettingsScreen(state: SettingsUiState, viewModel: SettingsViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Readaloud") }, navigationIcon = { IconButton(onClick = onBack) { Text("←") } }) }
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SettingsSectionHeader("Highlight color")
            Row(Modifier.padding(horizontal = 16.dp)) {
                HighlightColor.entries.forEach { color ->
                    FilterChip(
                        selected = state.readaloudHighlightColor == color,
                        onClick = { viewModel.setReadaloudHighlightColor(color) },
                        label = { Text(color.name) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            SettingsSectionHeader("Storyteller sources")
            state.sources.filter { it.isStorytellerService }.forEach { source ->
                SourceRow(source = source, onRemove = { viewModel.removeSource(source.id) })
            }
        }
    }
}
```

- [ ] **Step 3: Compile + commit**

```bash
git add shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/ReadaloudSection.kt \
        shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/readaloud/
git commit -m "$(cat <<'EOF'
feat(ios): implement Readaloud settings section and screen
EOF
)"
```

---

## Task 14: Annotations Sync section + drill-in screen

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/AnnotationsSyncSection.kt`
- Create: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/annotationsync/AnnotationsSyncSettingsScreen.kt`

- [ ] **Step 1: Implement `AnnotationsSyncSection`**

```kotlin
@Composable
fun AnnotationsSyncSection(configured: Boolean, onNavigate: () -> Unit) {
    Column {
        SettingsSectionHeader("Annotations sync")
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onNavigate).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("WebDAV sync", style = MaterialTheme.typography.bodyMedium)
                Text(if (configured) "Configured" else "Not configured", style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
```

- [ ] **Step 2: Implement `AnnotationsSyncSettingsScreen`**

Inject `AnnotationSyncConfigStore` directly (via `koinInject`) — this screen manages its own state. Show URL, username, and password text fields; Save and Clear buttons.

```kotlin
@Composable
fun AnnotationsSyncSettingsScreen(onBack: () -> Unit) {
    val configStore = koinInject<AnnotationSyncConfigStore>()
    val config by configStore.observe().collectAsState(null)
    var url by remember(config) { mutableStateOf(config?.url ?: "") }
    var username by remember(config) { mutableStateOf(config?.username ?: "") }
    var password by remember(config) { mutableStateOf(config?.password ?: "") }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Annotations sync") }, navigationIcon = { IconButton(onClick = onBack) { Text("←") } }) }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("WebDAV URL") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch { configStore.save(AnnotationSyncConfig(url = url, username = username, password = password)) }
                }) { Text("Save") }
                if (config != null) {
                    OutlinedButton(onClick = { scope.launch { configStore.clear() } }) { Text("Clear") }
                }
            }
        }
    }
}
```

Add iOS no-op `AnnotationSyncConfigStore` to `IosNoOpImpls.kt` if not already registered:

```kotlin
internal class IosNoOpAnnotationSyncConfigStore : AnnotationSyncConfigStore {
    override fun observe(): StateFlow<AnnotationSyncConfig?> = MutableStateFlow(null)
    override suspend fun save(config: AnnotationSyncConfig) {}
    override suspend fun clear() {}
}
```

Register in Koin: `single<AnnotationSyncConfigStore> { IosNoOpAnnotationSyncConfigStore() }`

- [ ] **Step 3: Compile + commit**

```bash
git add shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/AnnotationsSyncSection.kt \
        shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/annotationsync/ \
        shared/src/iosMain/kotlin/com/riffle/shared/library/IosNoOpImpls.kt \
        shared/src/iosMain/kotlin/com/riffle/shared/Koin.kt
git commit -m "$(cat <<'EOF'
feat(ios): implement Annotations Sync settings section and screen
EOF
)"
```

---

## Task 15: App Version section + `expect/actual PlatformSettingsSections`

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/sections/AppVersionSection.kt`
- Create: `shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/PlatformSettingsSections.kt` (expect declaration)
- Create: `shared/src/androidMain/kotlin/com/riffle/shared/feature/settings/PlatformSettingsSections.android.kt`
- Create: `shared/src/iosMain/kotlin/com/riffle/shared/feature/settings/PlatformSettingsSections.ios.kt`

- [ ] **Step 1: Implement `AppVersionSection`**

```kotlin
@Composable
fun AppVersionSection() {
    Column {
        SettingsSectionHeader("About")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Version", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(AppVersion.name, style = MaterialTheme.typography.bodySmall)
        }
    }
}
```

Add `expect val AppVersion: AppVersionInfo` with `data class AppVersionInfo(val name: String)` in `commonMain`. Provide `actual val AppVersion = AppVersionInfo(BuildConfig.VERSION_NAME)` in `androidMain` and `actual val AppVersion = AppVersionInfo("1.0")` in `iosMain` (replace with real version reading from `CFBundleShortVersionString` if available via `platform.Foundation.NSBundle`).

- [ ] **Step 2: Declare `expect fun PlatformSettingsSections`**

```kotlin
// shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/PlatformSettingsSections.kt
package com.riffle.shared.feature.settings

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformSettingsSections(viewModel: SettingsViewModel)
```

- [ ] **Step 3: Android actual — App Behavior + App Update rows**

Note: `SettingsDivider` is `private` in `SettingsScreen.kt` — do NOT call it from this file. Inline the divider directly:

```kotlin
// shared/src/androidMain/kotlin/com/riffle/shared/feature/settings/PlatformSettingsSections.android.kt
package com.riffle.shared.feature.settings

import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
actual fun PlatformSettingsSections(viewModel: SettingsViewModel) {
    AppBehaviorSection(viewModel = viewModel)
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    AppUpdateSection(viewModel = viewModel)
}
```

`AppBehaviorSection` shows Keep Screen On toggle and Volume Key navigation (Android-only toggles from `WakeLockPreferencesStore` / `VolumeKeyPreferencesStore`). These stores are already registered in the Android Koin module. For now, stub the composables (they can delegate to the existing Android `app/` module's sections or be re-implemented as thin composables reading from the shared VM — but since these stores are not in `SettingsViewModel`, inject them separately via `koinInject()` inside the composable).

`AppUpdateSection` shows the version check / install UI (inject `AppUpdateRepository` from Android Koin via `koinInject()`).

- [ ] **Step 4: iOS actual — empty**

```kotlin
// shared/src/iosMain/kotlin/com/riffle/shared/feature/settings/PlatformSettingsSections.ios.kt
package com.riffle.shared.feature.settings

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformSettingsSections(viewModel: SettingsViewModel) {
    // No Android-only sections on iOS.
}
```

- [ ] **Step 5: Full test suite + compile**

```bash
./gradlew test jvmTest :shared:compileKotlinIosSimulatorArm64 2>&1 | tail -30
```

Expected: all tests pass, iOS target compiles.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/riffle/shared/feature/settings/ \
        shared/src/androidMain/kotlin/com/riffle/shared/feature/settings/ \
        shared/src/iosMain/kotlin/com/riffle/shared/feature/settings/
git commit -m "$(cat <<'EOF'
feat(ios): add App Version section and platform-guarded Android-only settings
EOF
)"
```

---

## Task 16: iOS XCTest scenarios

**Files:**
- Create: `docs/testing/ios-scenarios/N-settings-downloads.md`
- Create: `iosApp/iosAppTests/SettingsTests.swift`
- Create: `iosApp/iosAppTests/DownloadsTests.swift`

**Interfaces:**
- Consumes: all screens from Tasks 5–15

- [ ] **Step 1: Write scenario doc**

```markdown
# Settings & Downloads — iOS Scenarios

## Settings navigation
1. Open the app and tap the hamburger/drawer icon.
2. Tap "Settings" in the drawer.
3. Verify the Settings screen appears with a "Sources" section.
4. Tap "Reading" row → verify the Reading section rows are visible.
5. Tap "Formatting" → verify the bottom sheet opens with a font size slider.
6. Dismiss the sheet.
7. Tap "Listening" row → tap "Listening preferences" → verify the Listening panel opens.
8. Tap "Readaloud" row → verify Readaloud Settings screen with highlight color chips.
9. Tap back → verify return to Settings root.
10. Tap "←" → verify return to Library.

## Downloads navigation
1. Open drawer → tap "Downloads".
2. Verify the Downloads screen with "Downloaded" and "Cached" section headers.
3. Verify empty-state messages when there are no items.
4. Tap "Cache settings: After 30 days" → verify the dialog opens with radio buttons.
5. Select "Off" → verify the button label updates.
6. Tap "←" → verify return to Library.
```

Save to `docs/testing/ios-scenarios/` — find the next sequential `N` by listing existing files.

- [ ] **Step 2: Implement `SettingsTests.swift`**

```swift
// iosApp/iosAppTests/SettingsTests.swift
import XCTest

final class SettingsTests: XCTestCase {
    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    func testOpenSettingsFromDrawer() throws {
        // Open drawer (tap hamburger button or swipe from left edge)
        let drawerButton = app.buttons["Open navigation drawer"]
        XCTAssertTrue(drawerButton.waitForExistence(timeout: 5))
        drawerButton.tap()

        // Tap Settings
        let settingsRow = app.staticTexts["Settings"]
        XCTAssertTrue(settingsRow.waitForExistence(timeout: 3))
        settingsRow.tap()

        // Verify Settings screen header
        XCTAssertTrue(app.staticTexts["Settings"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["Sources"].exists)
    }

    func testFormattingPanelOpens() throws {
        openSettings()
        app.staticTexts["Formatting"].tap()
        XCTAssertTrue(app.staticTexts["Formatting"].waitForExistence(timeout: 3))
        // Verify slider is present
        XCTAssertTrue(app.sliders.firstMatch.exists)
    }

    func testReadaloudDrillIn() throws {
        openSettings()
        app.staticTexts["Readaloud settings"].tap()
        XCTAssertTrue(app.staticTexts["Readaloud"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["Highlight color"].exists)
    }

    func testBackFromSettings() throws {
        openSettings()
        app.buttons.matching(identifier: "back_button").firstMatch.tap()
        // Should return to library
        XCTAssertFalse(app.staticTexts["Settings"].waitForExistence(timeout: 2))
    }

    private func openSettings() {
        app.buttons["Open navigation drawer"].tap()
        app.staticTexts["Settings"].tap()
    }
}
```

- [ ] **Step 3: Implement `DownloadsTests.swift`**

```swift
// iosApp/iosAppTests/DownloadsTests.swift
import XCTest

final class DownloadsTests: XCTestCase {
    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    func testOpenDownloadsFromDrawer() throws {
        app.buttons["Open navigation drawer"].tap()
        let downloadsRow = app.staticTexts["Downloads"]
        XCTAssertTrue(downloadsRow.waitForExistence(timeout: 3))
        downloadsRow.tap()

        XCTAssertTrue(app.staticTexts["Downloaded"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["Cached"].exists)
    }

    func testEmptyDownloadsShowsEmptyMessage() throws {
        openDownloads()
        XCTAssertTrue(app.staticTexts["No downloaded items"].waitForExistence(timeout: 3))
    }

    func testCacheSettingsDialogOpens() throws {
        openDownloads()
        app.buttons.matching(NSPredicate(format: "label CONTAINS 'Cache settings'")).firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Auto-clear cache"].waitForExistence(timeout: 3))
        // Radio buttons present
        XCTAssertTrue(app.staticTexts["Off"].exists)
        XCTAssertTrue(app.staticTexts["After 30 days"].exists)
    }

    func testBackFromDownloads() throws {
        openDownloads()
        app.buttons.matching(identifier: "back_button").firstMatch.tap()
        XCTAssertFalse(app.staticTexts["Downloaded"].waitForExistence(timeout: 2))
    }

    private func openDownloads() {
        app.buttons["Open navigation drawer"].tap()
        app.staticTexts["Downloads"].tap()
    }
}
```

- [ ] **Step 4: Run iOS tests**

```bash
xcodebuild test \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 16" \
  -testPlan iosAppTests \
  ONLY_TESTING:iosAppTests/SettingsTests,iosAppTests/DownloadsTests \
  2>&1 | tail -30
```

Expected: all 8 tests pass.

- [ ] **Step 5: Run full Android test suite to confirm no regressions**

```bash
./gradlew test jvmTest 2>&1 | tail -20
```

- [ ] **Step 6: Final commit**

```bash
git add docs/testing/ios-scenarios/ \
        iosApp/iosAppTests/SettingsTests.swift \
        iosApp/iosAppTests/DownloadsTests.swift
git commit -m "$(cat <<'EOF'
test(ios): add XCTest scenarios for Settings and Downloads screens
EOF
)"
```

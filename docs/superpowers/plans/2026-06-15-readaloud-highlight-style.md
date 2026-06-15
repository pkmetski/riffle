# Readaloud Highlight Style Setting — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hardcoded readaloud sentence-highlight color with a user-configurable preset stored in a new `ReadaloudPreferences` DataStore.

**Architecture:** New `ReadaloudPreferences` domain type + `ReadaloudPreferencesStore` interface in `core/domain`; `ReadaloudPreferencesStoreImpl` backed by a Jetpack DataStore Preferences instance in `core/data`; Hilt qualifier + binding in `DataModule`; `EpubReaderViewModel` exposes a `StateFlow<ReadaloudHighlightColor>` that drives the highlight `LaunchedEffect`; `SettingsViewModel` exposes the preferences and an update method; `SettingsScreen` shows an inline color-chip row under a new "Readaloud" section.

**Tech Stack:** Kotlin, Jetpack DataStore Preferences, Hilt, Compose Material 3, Readium `Decoration.Style.Highlight`

---

## File Map

| Action | Path |
|--------|------|
| **Create** | `core/domain/src/main/kotlin/com/riffle/core/domain/ReadaloudPreferences.kt` |
| **Create** | `core/data/src/main/kotlin/com/riffle/core/data/di/ReadaloudPreferencesDataStoreExt.kt` |
| **Create** | `core/data/src/main/kotlin/com/riffle/core/data/ReadaloudPreferencesStoreImpl.kt` |
| **Create** | `core/data/src/test/kotlin/com/riffle/core/data/ReadaloudPreferencesStoreTest.kt` |
| **Modify** | `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt` |
| **Modify** | `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsViewModel.kt` |
| **Modify** | `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt` |
| **Modify** | `app/src/test/kotlin/com/riffle/app/feature/settings/SettingsViewModelTest.kt` |
| **Modify** | `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` |
| **Modify** | `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt` |

---

## Task 1: Domain types

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/ReadaloudPreferences.kt`

No tests needed — pure data types with no logic.

- [ ] **Step 1: Create `ReadaloudPreferences.kt`**

```kotlin
package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

enum class ReadaloudHighlightColor(val argb: Int) {
    BLUE(0xFF7DD3FC.toInt()),
    YELLOW(0xFFFDE68A.toInt()),
    GREEN(0xFF86EFAC.toInt()),
    PINK(0xFFFDA4AF.toInt()),
    PURPLE(0xFFC4B5FD.toInt()),
}

data class ReadaloudPreferences(
    val highlightColor: ReadaloudHighlightColor = ReadaloudHighlightColor.BLUE,
)

interface ReadaloudPreferencesStore {
    val preferences: Flow<ReadaloudPreferences>
    suspend fun update(prefs: ReadaloudPreferences)
}
```

- [ ] **Step 2: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/ReadaloudPreferences.kt
git commit -m "feat(domain): add ReadaloudPreferences with highlight color enum and store interface"
```

---

## Task 2: DataStore implementation + tests

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/di/ReadaloudPreferencesDataStoreExt.kt`
- Create: `core/data/src/main/kotlin/com/riffle/core/data/ReadaloudPreferencesStoreImpl.kt`
- Create: `core/data/src/test/kotlin/com/riffle/core/data/ReadaloudPreferencesStoreTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `core/data/src/test/kotlin/com/riffle/core/data/ReadaloudPreferencesStoreTest.kt`:

```kotlin
package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReadaloudPreferencesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildStore() = ReadaloudPreferencesStoreImpl(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("readaloud_prefs.preferences_pb") },
        )
    )

    @Test
    fun `default preferences returned when DataStore is empty`() = testScope.runTest {
        assertEquals(ReadaloudPreferences(), buildStore().preferences.first())
    }

    @Test
    fun `each highlight color round-trips through DataStore`() = testScope.runTest {
        val store = buildStore()
        for (color in ReadaloudHighlightColor.entries) {
            store.update(ReadaloudPreferences(highlightColor = color))
            assertEquals(color, store.preferences.first().highlightColor)
        }
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:test --tests "com.riffle.core.data.ReadaloudPreferencesStoreTest" 2>&1 | tail -20
```

Expected: compilation failure — `ReadaloudPreferencesStoreImpl` does not exist yet.

- [ ] **Step 3: Create the DataStore extension**

Create `core/data/src/main/kotlin/com/riffle/core/data/di/ReadaloudPreferencesDataStoreExt.kt`:

```kotlin
package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.readaloudPreferencesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "readaloud_preferences")
```

- [ ] **Step 4: Create `ReadaloudPreferencesStoreImpl`**

Create `core/data/src/main/kotlin/com/riffle/core/data/ReadaloudPreferencesStoreImpl.kt`:

```kotlin
package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.data.di.ReadaloudPreferencesDataStore
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ReadaloudPreferencesStoreImpl @Inject constructor(
    @param:ReadaloudPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : ReadaloudPreferencesStore {

    override val preferences: Flow<ReadaloudPreferences> = dataStore.data.map { prefs ->
        ReadaloudPreferences(
            highlightColor = prefs[KEY_HIGHLIGHT_COLOR]
                ?.let { runCatching { ReadaloudHighlightColor.valueOf(it) }.getOrNull() }
                ?: ReadaloudHighlightColor.BLUE,
        )
    }

    override suspend fun update(prefs: ReadaloudPreferences) {
        dataStore.edit { it[KEY_HIGHLIGHT_COLOR] = prefs.highlightColor.name }
    }

    private companion object {
        val KEY_HIGHLIGHT_COLOR = stringPreferencesKey("highlight_color")
    }
}
```

Note: the test (`ReadaloudPreferencesStoreTest`) constructs `ReadaloudPreferencesStoreImpl` directly with a `DataStore<Preferences>` (not via the Hilt qualifier), so the `@param:ReadaloudPreferencesDataStore` annotation on the constructor is ignored in tests — this matches exactly how `FormattingPreferencesStoreTest` works.

- [ ] **Step 5: Run tests to confirm they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:test --tests "com.riffle.core.data.ReadaloudPreferencesStoreTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 2 tests passing.

- [ ] **Step 6: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/di/ReadaloudPreferencesDataStoreExt.kt \
        core/data/src/main/kotlin/com/riffle/core/data/ReadaloudPreferencesStoreImpl.kt \
        core/data/src/test/kotlin/com/riffle/core/data/ReadaloudPreferencesStoreTest.kt
git commit -m "feat(data): add ReadaloudPreferencesStoreImpl backed by DataStore"
```

---

## Task 3: Hilt DI wiring

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`

- [ ] **Step 1: Add qualifier annotation to `DataModule.kt`**

In `DataModule.kt`, add the new qualifier alongside the existing ones (e.g. after `CoverGridDensityDataStore` at line ~144):

```kotlin
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReadaloudPreferencesDataStore
```

- [ ] **Step 2: Add `@Binds` for the store and `@Provides` for the DataStore instance**

In the `DataModule` abstract class body, add after `bindCoverGridDensityStore` (around line 316):

```kotlin
@Binds
@Singleton
abstract fun bindReadaloudPreferencesStore(impl: ReadaloudPreferencesStoreImpl): ReadaloudPreferencesStore
```

In the `DataModule.companion object`, add after `provideCoverGridDensityDataStore`:

```kotlin
@Provides
@Singleton
@ReadaloudPreferencesDataStore
fun provideReadaloudPreferencesDataStore(
    @ApplicationContext context: Context
): DataStore<Preferences> = context.readaloudPreferencesDataStore
```

Also add the required imports at the top of `DataModule.kt`:

```kotlin
import com.riffle.core.data.ReadaloudPreferencesStoreImpl
import com.riffle.core.domain.ReadaloudPreferencesStore
```

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt
git commit -m "feat(di): wire ReadaloudPreferencesStore into Hilt graph"
```

---

## Task 4: Settings ViewModel + test

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsViewModel.kt`
- Modify: `app/src/test/kotlin/com/riffle/app/feature/settings/SettingsViewModelTest.kt`

- [ ] **Step 1: Write the failing test first**

In `SettingsViewModelTest.kt`, add the following. First add imports at the top:

```kotlin
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
```

Then add a fake store field alongside the other `noOp*` fields:

```kotlin
private val readaloudPrefsFlow = MutableStateFlow(ReadaloudPreferences())
private val fakeReadaloudStore = object : ReadaloudPreferencesStore {
    override val preferences = readaloudPrefsFlow
    override suspend fun update(prefs: ReadaloudPreferences) { readaloudPrefsFlow.value = prefs }
}
```

Then add the test:

```kotlin
@Test
fun `updateReadaloudHighlightColor persists new color to store`() = testScope.runTest {
    val vm = makeViewModel()
    vm.updateReadaloudHighlightColor(ReadaloudHighlightColor.YELLOW)
    testDispatcher.scheduler.advanceUntilIdle()
    assertEquals(ReadaloudHighlightColor.YELLOW, readaloudPrefsFlow.value.highlightColor)
}
```

- [ ] **Step 2: Run test to confirm it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.settings.SettingsViewModelTest.updateReadaloudHighlightColor*" 2>&1 | tail -20
```

Expected: compilation failure — `updateReadaloudHighlightColor` not on `SettingsViewModel` yet.

- [ ] **Step 3: Add `ReadaloudPreferencesStore` to `SettingsViewModel`**

In `SettingsViewModel.kt`:

Add import:
```kotlin
import com.riffle.core.domain.ReadaloudPreferences
import com.riffle.core.domain.ReadaloudPreferencesStore
import com.riffle.core.domain.ReadaloudHighlightColor
```

Add `readaloudPreferencesStore` to the constructor (after `appUpdateRepository`):

```kotlin
private val readaloudPreferencesStore: ReadaloudPreferencesStore,
```

Add the `StateFlow` property (alongside `globalFormattingPreferences`):

```kotlin
val readaloudPreferences: StateFlow<ReadaloudPreferences> =
    readaloudPreferencesStore.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadaloudPreferences())
```

Add the update function (alongside `updateGlobalFormatting`):

```kotlin
fun updateReadaloudHighlightColor(color: ReadaloudHighlightColor) {
    viewModelScope.launch {
        readaloudPreferencesStore.update(ReadaloudPreferences(highlightColor = color))
    }
}
```

- [ ] **Step 4: Update `makeViewModel()` in the test to pass `fakeReadaloudStore`**

In `SettingsViewModelTest.kt`, update `makeViewModel()` to include the new parameter:

```kotlin
private fun makeViewModel(report: CrashReport? = null) = SettingsViewModel(
    crashReportRepository = object : CrashReportRepository {
        override fun getLastCrashReport() = report
    },
    formattingPreferencesStore = noOpFormattingStore,
    serverRepository = fakeServerRepo(),
    libraryRepository = fakeLibraryRepo(),
    visibilityStore = fakeVisibilityStore(),
    orderStore = fakeOrderStore(),
    wakeLockPreferencesStore = noOpWakeLockStore,
    volumeKeyPreferencesStore = fakeVolumeKeyStore,
    appThemeStore = fakeAppThemeStore,
    readaloudReviewRepository = fakeReviewRepo,
    connectivityObserver = fakeConnectivity,
    appUpdateRepository = fakeAppUpdateRepo,
    readaloudPreferencesStore = fakeReadaloudStore,
)
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest --tests "com.riffle.app.feature.settings.SettingsViewModelTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all tests including the new one passing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/settings/SettingsViewModel.kt \
        app/src/test/kotlin/com/riffle/app/feature/settings/SettingsViewModelTest.kt
git commit -m "feat(settings): expose readaloud highlight color in SettingsViewModel"
```

---

## Task 5: Settings UI

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt`

- [ ] **Step 1: Add imports to `SettingsScreen.kt`**

Add these imports alongside the existing ones:

```kotlin
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferences
```

- [ ] **Step 2: Collect `readaloudPreferences` state**

In `SettingsScreen`, alongside the other `collectAsState()` calls near the top of the composable (after `val appUpdateState by viewModel.appUpdateState.collectAsState()`):

```kotlin
val readaloudPreferences by viewModel.readaloudPreferences.collectAsState()
```

- [ ] **Step 3: Add the "Readaloud" section after the "Reading" section**

In the `SettingsScreen` composable's `LazyColumn` / `Column`, after the existing `HorizontalDivider()` that follows the "Reading settings" `ListItem` (currently at line ~230):

```kotlin
Text(
    text = "Readaloud",
    style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
)
HorizontalDivider()
ListItem(
    headlineContent = { Text("Sentence highlight") },
    trailingContent = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadaloudHighlightColor.entries.forEach { color ->
                val isSelected = readaloudPreferences.highlightColor == color
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(color.argb.toLong() and 0xFFFFFFFFL))
                        .then(
                            if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                        .clickable { viewModel.updateReadaloudHighlightColor(color) },
                )
            }
        }
    },
)
HorizontalDivider()
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt
git commit -m "feat(settings): add Readaloud section with sentence highlight color picker"
```

---

## Task 6: Reader wiring

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

- [ ] **Step 1: Add `ReadaloudPreferencesStore` to `EpubReaderViewModel`**

In `EpubReaderViewModel.kt`, add the import:

```kotlin
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReadaloudPreferencesStore
```

Add a constructor parameter after `progressFlushScope` (the last param before `) : AndroidViewModel(application)`):

```kotlin
private val readaloudPreferencesStore: ReadaloudPreferencesStore,
```

Add the exposed `StateFlow` (alongside the other state flows, e.g. near `sentenceQuotes`):

```kotlin
val readaloudHighlightColor: StateFlow<ReadaloudHighlightColor> =
    readaloudPreferencesStore.preferences
        .map { it.highlightColor }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadaloudHighlightColor.BLUE)
```

The `map` import is already present in `EpubReaderViewModel.kt`.

- [ ] **Step 2: Collect `readaloudHighlightColor` in `EpubReaderScreen`**

In `EpubReaderScreen.kt`, add to the block of `collectAsState()` calls (near `val sentenceQuotes by viewModel.sentenceQuotes.collectAsState()`):

```kotlin
val readaloudHighlightColor by viewModel.readaloudHighlightColor.collectAsState()
```

- [ ] **Step 3: Replace hardcoded color and add to LaunchedEffect key**

Find the readaloud decoration `LaunchedEffect` (currently starting at line ~1278):

```kotlin
LaunchedEffect(activeFragmentRef, reflowGeneration, pageLoadGeneration.value, sentenceQuotes) {
```

Replace with:

```kotlin
LaunchedEffect(activeFragmentRef, reflowGeneration, pageLoadGeneration.value, sentenceQuotes, readaloudHighlightColor) {
```

Then at line ~1296, replace:

```kotlin
tint = android.graphics.Color.parseColor("#FF7DD3FC"),
```

with:

```kotlin
tint = readaloudHighlightColor.argb,
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt \
        app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(reader): use user-configured readaloud highlight color from ReadaloudPreferencesStore"
```

---

## Task 7: Verify all tests pass

- [ ] **Step 1: Run full JVM test suite**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL` with no failures. The two new test classes (`ReadaloudPreferencesStoreTest`, `SettingsViewModelTest` including the new test) should all be green.

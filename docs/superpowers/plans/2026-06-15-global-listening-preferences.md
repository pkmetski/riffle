# Global Listening Preferences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three global listening settings (default playback speed, skip interval, auto-rewind on resume) to the Settings screen, with the default speed flowing into both player ViewModels as the per-book fallback.

**Architecture:** A new `ListeningPreferencesStore` domain interface backed by a single DataStore file, following the identical pattern used by `WakeLockPreferencesStoreImpl` / `VolumeKeyPreferencesStoreImpl`. Settings are exposed in a new "Listening" section on the Settings screen via three inline `SingleChoiceSegmentedButtonRow` controls. Both `EpubReaderViewModel` and `AudiobookPlayerViewModel` receive the store via injection and swap their hardcoded `DEFAULT_PLAYBACK_SPEED` fallback for the live global value.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 `SingleChoiceSegmentedButtonRow`), AndroidX DataStore Preferences, Hilt

---

## File Map

| Status | File | Purpose |
|---|---|---|
| Create | `core/domain/src/main/kotlin/com/riffle/core/domain/ListeningPreferencesStore.kt` | Domain interface + constants |
| Create | `core/data/src/main/kotlin/com/riffle/core/data/di/ListeningPreferencesDataStoreExt.kt` | DataStore extension property |
| Create | `core/data/src/main/kotlin/com/riffle/core/data/ListeningPreferencesStoreImpl.kt` | DataStore-backed implementation |
| Create | `core/data/src/test/kotlin/com/riffle/core/data/ListeningPreferencesStoreTest.kt` | Unit tests |
| Modify | `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt` | Qualifier annotation + binding + provider |
| Modify | `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsViewModel.kt` | Inject store, expose 3 StateFlows + 3 setters |
| Modify | `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt` | "Listening" section with 3 segmented-button rows |
| Modify | `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt` | Inject store; replace hardcoded default fallback |
| Modify | `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt` | Inject store; replace hardcoded default fallback |

---

### Task 1: Domain interface

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/ListeningPreferencesStore.kt`

- [ ] **Step 1: Create the domain interface**

```kotlin
package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface ListeningPreferencesStore {

    /** Global default playback speed applied when no per-book override exists. */
    val defaultPlaybackSpeed: Flow<Float>

    /** Seconds the ⏪/⏩ skip buttons jump. */
    val skipIntervalSeconds: Flow<Int>

    /** Seconds to rewind when resuming after a pause or stop. */
    val rewindOnResumeSeconds: Flow<Int>

    suspend fun setDefaultPlaybackSpeed(speed: Float)
    suspend fun setSkipIntervalSeconds(seconds: Int)
    suspend fun setRewindOnResumeSeconds(seconds: Int)

    companion object {
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
        const val DEFAULT_SKIP_INTERVAL_SECONDS = 30
        const val DEFAULT_REWIND_ON_RESUME_SECONDS = 0
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/ListeningPreferencesStore.kt
git commit -m "feat(domain): add ListeningPreferencesStore interface"
```

---

### Task 2: DataStore extension + implementation

**Files:**
- Create: `core/data/src/main/kotlin/com/riffle/core/data/di/ListeningPreferencesDataStoreExt.kt`
- Create: `core/data/src/main/kotlin/com/riffle/core/data/ListeningPreferencesStoreImpl.kt`

- [ ] **Step 1: Create the DataStore extension property**

```kotlin
package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.listeningPreferencesDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "listening_preferences")
```

- [ ] **Step 2: Create the implementation**

```kotlin
package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.riffle.core.data.di.ListeningPreferencesDataStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_PLAYBACK_SPEED
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_REWIND_ON_RESUME_SECONDS
import com.riffle.core.domain.ListeningPreferencesStore.Companion.DEFAULT_SKIP_INTERVAL_SECONDS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ListeningPreferencesStoreImpl @Inject constructor(
    @param:ListeningPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : ListeningPreferencesStore {

    override val defaultPlaybackSpeed: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_PLAYBACK_SPEED] ?: DEFAULT_PLAYBACK_SPEED
    }

    override val skipIntervalSeconds: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_SKIP_INTERVAL_SECONDS] ?: DEFAULT_SKIP_INTERVAL_SECONDS
    }

    override val rewindOnResumeSeconds: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_REWIND_ON_RESUME_SECONDS] ?: DEFAULT_REWIND_ON_RESUME_SECONDS
    }

    override suspend fun setDefaultPlaybackSpeed(speed: Float) {
        dataStore.edit { prefs -> prefs[KEY_DEFAULT_PLAYBACK_SPEED] = speed }
    }

    override suspend fun setSkipIntervalSeconds(seconds: Int) {
        dataStore.edit { prefs -> prefs[KEY_SKIP_INTERVAL_SECONDS] = seconds }
    }

    override suspend fun setRewindOnResumeSeconds(seconds: Int) {
        dataStore.edit { prefs -> prefs[KEY_REWIND_ON_RESUME_SECONDS] = seconds }
    }

    private companion object {
        val KEY_DEFAULT_PLAYBACK_SPEED = floatPreferencesKey("default_playback_speed")
        val KEY_SKIP_INTERVAL_SECONDS = intPreferencesKey("skip_interval_seconds")
        val KEY_REWIND_ON_RESUME_SECONDS = intPreferencesKey("rewind_on_resume_seconds")
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/di/ListeningPreferencesDataStoreExt.kt
git add core/data/src/main/kotlin/com/riffle/core/data/ListeningPreferencesStoreImpl.kt
git commit -m "feat(data): add ListeningPreferencesStoreImpl backed by DataStore"
```

---

### Task 3: Unit tests

**Files:**
- Create: `core/data/src/test/kotlin/com/riffle/core/data/ListeningPreferencesStoreTest.kt`

The test follows the exact pattern of `WakeLockPreferencesStoreTest` — a `PreferenceDataStoreFactory` with `TemporaryFolder`, `TestScope(UnconfinedTestDispatcher())`, and a `buildStore()` helper.

- [ ] **Step 1: Write the tests**

```kotlin
package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.riffle.core.domain.ListeningPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningPreferencesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildStore() = ListeningPreferencesStoreImpl(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("listening_prefs.preferences_pb") },
        )
    )

    @Test
    fun `default playback speed is 1_0 when DataStore is empty`() = testScope.runTest {
        assertEquals(1.0f, buildStore().defaultPlaybackSpeed.first())
    }

    @Test
    fun `setDefaultPlaybackSpeed persists and reads back`() = testScope.runTest {
        val store = buildStore()
        store.setDefaultPlaybackSpeed(1.5f)
        assertEquals(1.5f, store.defaultPlaybackSpeed.first())
    }

    @Test
    fun `default skipIntervalSeconds is 30 when DataStore is empty`() = testScope.runTest {
        assertEquals(30, buildStore().skipIntervalSeconds.first())
    }

    @Test
    fun `setSkipIntervalSeconds persists and reads back`() = testScope.runTest {
        val store = buildStore()
        store.setSkipIntervalSeconds(15)
        assertEquals(15, store.skipIntervalSeconds.first())
    }

    @Test
    fun `default rewindOnResumeSeconds is 0 when DataStore is empty`() = testScope.runTest {
        assertEquals(0, buildStore().rewindOnResumeSeconds.first())
    }

    @Test
    fun `setRewindOnResumeSeconds persists and reads back`() = testScope.runTest {
        val store = buildStore()
        store.setRewindOnResumeSeconds(10)
        assertEquals(10, store.rewindOnResumeSeconds.first())
    }

    @Test
    fun `settings persist across store instances`() {
        val file = tmp.newFile("listening_round_trip.preferences_pb")

        val writeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        runBlocking {
            val store = ListeningPreferencesStoreImpl(
                PreferenceDataStoreFactory.create(scope = writeScope, produceFile = { file })
            )
            store.setDefaultPlaybackSpeed(2.0f)
            store.setSkipIntervalSeconds(45)
            store.setRewindOnResumeSeconds(5)
        }
        writeScope.cancel()

        val readScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val store2 = ListeningPreferencesStoreImpl(
            PreferenceDataStoreFactory.create(scope = readScope, produceFile = { file })
        )
        assertEquals(2.0f, runBlocking { store2.defaultPlaybackSpeed.first() })
        assertEquals(45, runBlocking { store2.skipIntervalSeconds.first() })
        assertEquals(5, runBlocking { store2.rewindOnResumeSeconds.first() })
        readScope.cancel()
    }
}
```

- [ ] **Step 2: Run the tests to verify they pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:test --tests "com.riffle.core.data.ListeningPreferencesStoreTest"
```

Expected: all 8 tests PASS.

- [ ] **Step 3: Commit**

```bash
git add core/data/src/test/kotlin/com/riffle/core/data/ListeningPreferencesStoreTest.kt
git commit -m "test(data): add ListeningPreferencesStoreTest"
```

---

### Task 4: DI registration in DataModule

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`

DataModule has three regions to edit:
1. The qualifier annotations block (around line 132) — add `@ListeningPreferencesDataStore`
2. The `@Binds` abstract functions block (around line 304) — add a binding for `ListeningPreferencesStore`
3. The `@Provides` companion object (around line 628) — add a provider for the DataStore instance

- [ ] **Step 1: Add the qualifier annotation**

Find the block that declares `@WakeLockPreferencesDataStore` and `@VolumeKeyPreferencesDataStore`, and add directly below:

```kotlin
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ListeningPreferencesDataStore
```

- [ ] **Step 2: Add the `@Binds` binding**

Find the line:
```kotlin
    abstract fun bindVolumeKeyPreferencesStore(impl: VolumeKeyPreferencesStoreImpl): VolumeKeyPreferencesStore
```
Add below it:

```kotlin
    @Binds
    @Singleton
    abstract fun bindListeningPreferencesStore(impl: ListeningPreferencesStoreImpl): ListeningPreferencesStore
```

Also add the required imports at the top of DataModule.kt:
```kotlin
import com.riffle.core.data.ListeningPreferencesStoreImpl
import com.riffle.core.domain.ListeningPreferencesStore
```

- [ ] **Step 3: Add the `@Provides` DataStore provider**

Find the `provideVolumeKeyPreferencesDataStore` function and add below it:

```kotlin
        @Provides
        @Singleton
        @ListeningPreferencesDataStore
        fun provideListeningPreferencesDataStore(
            @ApplicationContext context: Context
        ): DataStore<Preferences> = context.listeningPreferencesDataStore
```

Also add the import:
```kotlin
import com.riffle.core.data.di.listeningPreferencesDataStore
```

- [ ] **Step 4: Verify the build compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt
git commit -m "feat(data): register ListeningPreferencesStore in DI"
```

---

### Task 5: SettingsViewModel

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsViewModel.kt`

- [ ] **Step 1: Add the constructor parameter**

Find the existing constructor parameter list. Add `listeningPreferencesStore` after `volumeKeyPreferencesStore`:

```kotlin
    private val listeningPreferencesStore: ListeningPreferencesStore,
```

Add the import:
```kotlin
import com.riffle.core.domain.ListeningPreferencesStore
```

- [ ] **Step 2: Expose three StateFlows**

Find the block where `keepScreenOn`, `volumeKeyNavigationEnabled`, and `invertVolumeKeys` are declared and add below them:

```kotlin
    val defaultPlaybackSpeed: StateFlow<Float> = listeningPreferencesStore.defaultPlaybackSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListeningPreferencesStore.DEFAULT_PLAYBACK_SPEED)

    val skipIntervalSeconds: StateFlow<Int> = listeningPreferencesStore.skipIntervalSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListeningPreferencesStore.DEFAULT_SKIP_INTERVAL_SECONDS)

    val rewindOnResumeSeconds: StateFlow<Int> = listeningPreferencesStore.rewindOnResumeSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListeningPreferencesStore.DEFAULT_REWIND_ON_RESUME_SECONDS)
```

- [ ] **Step 3: Add three setter methods**

Find the block where `setKeepScreenOn`, `setVolumeKeyNavigationEnabled`, and `setInvertVolumeKeys` are declared and add below them:

```kotlin
    fun setDefaultPlaybackSpeed(speed: Float) {
        viewModelScope.launch { listeningPreferencesStore.setDefaultPlaybackSpeed(speed) }
    }

    fun setSkipIntervalSeconds(seconds: Int) {
        viewModelScope.launch { listeningPreferencesStore.setSkipIntervalSeconds(seconds) }
    }

    fun setRewindOnResumeSeconds(seconds: Int) {
        viewModelScope.launch { listeningPreferencesStore.setRewindOnResumeSeconds(seconds) }
    }
```

- [ ] **Step 4: Verify build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/settings/SettingsViewModel.kt
git commit -m "feat(settings): expose listening preference StateFlows in SettingsViewModel"
```

---

### Task 6: SettingsScreen UI

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt`

The "Listening" section goes between the existing "Reading" `HorizontalDivider` (after the reading `ListItem`) and the "Crash reports" section header.

- [ ] **Step 1: Collect the three new StateFlows in the composable**

Find the existing `collectAsState()` calls near line 77 and add:

```kotlin
    val defaultPlaybackSpeed by viewModel.defaultPlaybackSpeed.collectAsState()
    val skipIntervalSeconds by viewModel.skipIntervalSeconds.collectAsState()
    val rewindOnResumeSeconds by viewModel.rewindOnResumeSeconds.collectAsState()
```

Add the import (if not present — `PlaybackSpeed` lives in the same app module):
```kotlin
import com.riffle.app.feature.audio.PlaybackSpeed
```

- [ ] **Step 2: Add the Listening section to the column body**

Find this code:
```kotlin
                HorizontalDivider()

                Text(
                    text = "Crash reports",
```
Insert the following block immediately before it:

```kotlin
                Text(
                    text = "Listening",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
                ListeningSpeedRow(
                    defaultSpeed = defaultPlaybackSpeed,
                    onDefaultSpeedChange = { viewModel.setDefaultPlaybackSpeed(it) },
                )
                HorizontalDivider()
                ListeningSkipIntervalRow(
                    skipIntervalSeconds = skipIntervalSeconds,
                    onSkipIntervalSecondsChange = { viewModel.setSkipIntervalSeconds(it) },
                )
                HorizontalDivider()
                ListeningRewindRow(
                    rewindOnResumeSeconds = rewindOnResumeSeconds,
                    onRewindOnResumeSecondsChange = { viewModel.setRewindOnResumeSeconds(it) },
                )
                HorizontalDivider()

```

- [ ] **Step 3: Add the three private composables at the bottom of the file**

Add after the closing brace of `AppThemeRow`:

```kotlin
@Composable
private fun ListeningSpeedRow(
    defaultSpeed: Float,
    onDefaultSpeedChange: (Float) -> Unit,
) {
    ListItem(
        headlineContent = { Text("Default speed") },
        supportingContent = { Text("Starting speed when opening a book for the first time") },
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        PlaybackSpeed.PRESETS.forEachIndexed { index, speed ->
            SegmentedButton(
                selected = speed == defaultSpeed,
                onClick = { onDefaultSpeedChange(speed) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = PlaybackSpeed.PRESETS.size),
            ) {
                Text(PlaybackSpeed.label(speed))
            }
        }
    }
}

@Composable
private fun ListeningSkipIntervalRow(
    skipIntervalSeconds: Int,
    onSkipIntervalSecondsChange: (Int) -> Unit,
) {
    val options = listOf(10, 15, 30, 45, 60)
    ListItem(
        headlineContent = { Text("Skip interval") },
        supportingContent = { Text("Seconds the ⏪/⏩ buttons jump") },
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        options.forEachIndexed { index, seconds ->
            SegmentedButton(
                selected = seconds == skipIntervalSeconds,
                onClick = { onSkipIntervalSecondsChange(seconds) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text("${seconds}s")
            }
        }
    }
}

@Composable
private fun ListeningRewindRow(
    rewindOnResumeSeconds: Int,
    onRewindOnResumeSecondsChange: (Int) -> Unit,
) {
    val options = listOf(0, 5, 10, 30)
    ListItem(
        headlineContent = { Text("Rewind on resume") },
        supportingContent = { Text("Seconds to rewind when resuming after a pause") },
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        options.forEachIndexed { index, seconds ->
            SegmentedButton(
                selected = seconds == rewindOnResumeSeconds,
                onClick = { onRewindOnResumeSecondsChange(seconds) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(if (seconds == 0) "Off" else "${seconds}s")
            }
        }
    }
}
```

- [ ] **Step 4: Verify build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt
git commit -m "feat(settings): add Listening section with speed, skip interval, and rewind settings"
```

---

### Task 7: Player integration — use global default as per-book fallback

The two spots that hardcode `AudioPlaybackPreferencesStore.DEFAULT_PLAYBACK_SPEED` as the per-book fallback need to use the global `ListeningPreferencesStore.defaultPlaybackSpeed` instead. Both are inside `suspend`-context coroutine blocks, so `.first()` is safe.

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt`

#### EpubReaderViewModel

- [ ] **Step 1: Add constructor parameter**

Find `private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,` in the constructor and add after it:

```kotlin
    private val listeningPreferencesStore: ListeningPreferencesStore,
```

Add the import:
```kotlin
import com.riffle.core.domain.ListeningPreferencesStore
```

- [ ] **Step 2: Replace the hardcoded fallback (line 449)**

Find:
```kotlin
            initialSpeed = audioPlaybackPreferencesStore.load(audioSettingsIdentity)
                ?: AudioPlaybackPreferencesStore.DEFAULT_PLAYBACK_SPEED
```
Replace with:
```kotlin
            initialSpeed = audioPlaybackPreferencesStore.load(audioSettingsIdentity)
                ?: listeningPreferencesStore.defaultPlaybackSpeed.first()
```

Also add the import at the top of the file if not already present:
```kotlin
import kotlinx.coroutines.flow.first
```

#### AudiobookPlayerViewModel

- [ ] **Step 3: Add constructor parameter**

Find `private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,` in the constructor and add after it:

```kotlin
    private val listeningPreferencesStore: ListeningPreferencesStore,
```

Add the imports:
```kotlin
import com.riffle.core.domain.ListeningPreferencesStore
import kotlinx.coroutines.flow.first
```

- [ ] **Step 4: Replace the hardcoded fallback (line 452)**

Find:
```kotlin
            val initialSpeed = audioPlaybackPreferencesStore.load(audioSettingsIdentity)
                ?: AudioPlaybackPreferencesStore.DEFAULT_PLAYBACK_SPEED
```
Replace with:
```kotlin
            val initialSpeed = audioPlaybackPreferencesStore.load(audioSettingsIdentity)
                ?: listeningPreferencesStore.defaultPlaybackSpeed.first()
```

- [ ] **Step 5: Verify the full build and unit tests pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test
```

Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git add app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt
git commit -m "feat(player): use global default speed as per-book playback speed fallback"
```

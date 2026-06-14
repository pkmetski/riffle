# Reading Time Estimate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show chapter and book time remaining in a pill beneath the chapter title in the reader; estimated from an adaptive per-device reading speed, or exact (teal) when read aloud is active.

**Architecture:** Three new domain types (`TimeRemaining`, `ReadingSpeedStore`, `ReadingSpeedTracker`) feed two new `StateFlow`s on `EpubReaderViewModel`. The display toggle (`showReadingTimeEstimate`) is wired into the existing formatting-preferences system (global DataStore + per-book Room override). A Room migration 35→36 adds the nullable column.

**Tech Stack:** Kotlin, Jetpack Compose, Room, DataStore Preferences, Hilt, Readium SDK (positions). All new logic is in JVM unit tests; the Room migration gets a connected test via the existing `MigrationTest` harness.

---

## File Map

### New files
| File | Purpose |
|---|---|
| `core/domain/…/TimeRemaining.kt` | Sealed class: `Estimated(sec)` / `Exact(sec)` |
| `core/domain/…/ReadingSpeedTracker.kt` | Pure EWMA logic; JVM-testable |
| `core/domain/…/ReadingSpeedStore.kt` | Interface: `speedSecPerPosition: Flow<Double>`, `updateSpeed(Double)` |
| `core/data/…/ReadingSpeedStoreImpl.kt` | DataStore-backed impl |
| `core/data/…/di/ReadingSpeedDataStoreExt.kt` | Context extension `readingSpeedDataStore` |
| `core/domain/src/test/…/ReadingSpeedTrackerTest.kt` | JVM tests for EWMA |
| `core/data/src/test/…/ReadingSpeedStoreTest.kt` | JVM tests for DataStore round-trip |

### Modified files
| File | Change |
|---|---|
| `core/domain/…/FormattingPreferences.kt` | Add `showReadingTimeEstimate: Boolean = true` |
| `core/domain/…/BookFormattingOverrides.kt` | Add nullable field; wire into `isEmpty`, `applyTo`, `withChanges` |
| `core/domain/src/test/…/BookFormattingOverridesTest.kt` | Extend for new field |
| `core/database/…/BookFormattingPreferencesEntity.kt` | Add `showReadingTimeEstimate: Boolean?` |
| `core/database/…/RiffleDatabase.kt` | Bump to v36, add `MIGRATION_35_36` |
| `core/database/src/androidTest/…/MigrationTest.kt` | Add step test + chain test |
| `core/data/…/FormattingPreferencesStoreImpl.kt` | Add key + read/write |
| `core/data/…/di/DataModule.kt` | Add qualifier, DataStore provider, bind `ReadingSpeedStore` |
| `app/…/reader/EpubReaderViewModel.kt` | Session tracking; `_readaloudTrackFlow`; `chapterTimeRemaining`/`bookTimeRemaining` flows |
| `app/…/reader/FormattingPanel.kt` | Add "Time remaining" toggle row |
| `app/…/reader/EpubReaderScreen.kt` | Collect flows; update visibility guard; pass to `ReadingProgressLabels` |

---

## Task 1: `TimeRemaining` sealed class

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/TimeRemaining.kt`

- [ ] **Step 1: Create the sealed class**

```kotlin
package com.riffle.core.domain

sealed class TimeRemaining(val sec: Long) {
    class Estimated(sec: Long) : TimeRemaining(sec)
    class Exact(sec: Long) : TimeRemaining(sec)
}
```

- [ ] **Step 2: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/TimeRemaining.kt
git commit -m "feat(reader): add TimeRemaining sealed class"
```

---

## Task 2: `ReadingSpeedTracker` (pure) + JVM tests

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/ReadingSpeedTracker.kt`
- Create: `core/domain/src/test/kotlin/com/riffle/core/domain/ReadingSpeedTrackerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.riffle.core.domain

import org.junit.Assert.*
import org.junit.Test

class ReadingSpeedTrackerTest {

    @Test
    fun `valid session blends observed rate into prior via EWMA`() {
        // Prior = 63.0 s/pos. Observed = 50.0 s/pos over 25 positions in 1250s.
        // New = 0.2 * 50 + 0.8 * 63 = 10 + 50.4 = 60.4
        val result = ReadingSpeedTracker.recordSession(
            progressDelta = 25f / 500f,   // 25 positions out of 500 total
            timeDeltaSec = 1250.0,
            totalPositions = 500f,
            priorSecPerPosition = 63.0,
        )
        assertNotNull(result)
        assertEquals(60.4, result!!, 0.01)
    }

    @Test
    fun `session shorter than 30 seconds is discarded`() {
        val result = ReadingSpeedTracker.recordSession(
            progressDelta = 10f / 500f,
            timeDeltaSec = 29.0,
            totalPositions = 500f,
            priorSecPerPosition = 63.0,
        )
        assertNull(result)
    }

    @Test
    fun `session moving fewer than 0·5 positions is discarded`() {
        val result = ReadingSpeedTracker.recordSession(
            progressDelta = 0.4f / 500f,
            timeDeltaSec = 60.0,
            totalPositions = 500f,
            priorSecPerPosition = 63.0,
        )
        assertNull(result)
    }

    @Test
    fun `session implying fewer than 20 WPM is discarded (left book open)`() {
        // 20 WPM → 250 words/pos ÷ 20 WPM × 60 = 750 s/pos. Just over that → discard.
        val result = ReadingSpeedTracker.recordSession(
            progressDelta = 1f / 500f,       // 1 position
            timeDeltaSec = 760.0,            // 760 s/pos → ~19.7 WPM → discard
            totalPositions = 500f,
            priorSecPerPosition = 63.0,
        )
        assertNull(result)
    }

    @Test
    fun `session implying more than 1000 WPM is discarded (scanning)`() {
        // 1000 WPM → 250/1000×60 = 15 s/pos. Just under that → discard.
        val result = ReadingSpeedTracker.recordSession(
            progressDelta = 1f / 500f,
            timeDeltaSec = 14.0,             // faster than 30s guard too — caught by min-time first
            totalPositions = 500f,
            priorSecPerPosition = 63.0,
        )
        assertNull(result)
    }

    @Test
    fun `session at exactly the WPM upper bound is kept`() {
        // 1000 WPM → 15 s/pos. timeDelta = 15s × 1 pos = 15s — but min-time is 30s, so need 2+ pos.
        // 2 positions at 1000 WPM → 30s exactly. Should be kept (boundary inclusive).
        val result = ReadingSpeedTracker.recordSession(
            progressDelta = 2f / 500f,
            timeDeltaSec = 30.0,
            totalPositions = 500f,
            priorSecPerPosition = 63.0,
        )
        assertNotNull(result)
    }

    @Test
    fun `default secs per position constant equals 63`() {
        assertEquals(63.0, ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION, 0.001)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:domain:test --tests "com.riffle.core.domain.ReadingSpeedTrackerTest" 2>&1 | tail -20
```

Expected: FAIL with "object ReadingSpeedTracker is not defined" (or similar).

- [ ] **Step 3: Implement `ReadingSpeedTracker`**

```kotlin
package com.riffle.core.domain

object ReadingSpeedTracker {

    const val DEFAULT_SECS_PER_POSITION = 63.0  // 250 words ÷ 238 WPM × 60s

    private const val MIN_SESSION_SEC = 30.0
    private const val MIN_POSITIONS_DELTA = 0.5
    private const val ALPHA = 0.2
    private const val WORDS_PER_POSITION = 250.0
    private const val MIN_WPM = 20.0
    private const val MAX_WPM = 1000.0

    /**
     * Returns the updated EWMA rate (seconds per Readium position), or null if the session
     * should be discarded as unreliable.
     */
    fun recordSession(
        progressDelta: Float,
        timeDeltaSec: Double,
        totalPositions: Float,
        priorSecPerPosition: Double,
    ): Double? {
        if (timeDeltaSec < MIN_SESSION_SEC) return null
        val positionsDelta = progressDelta * totalPositions
        if (positionsDelta < MIN_POSITIONS_DELTA) return null
        val observedRate = timeDeltaSec / positionsDelta
        val impliedWpm = (WORDS_PER_POSITION / observedRate) * 60.0
        if (impliedWpm < MIN_WPM || impliedWpm > MAX_WPM) return null
        return ALPHA * observedRate + (1.0 - ALPHA) * priorSecPerPosition
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :core:domain:test --tests "com.riffle.core.domain.ReadingSpeedTrackerTest" 2>&1 | tail -10
```

Expected: all 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/ReadingSpeedTracker.kt \
        core/domain/src/test/kotlin/com/riffle/core/domain/ReadingSpeedTrackerTest.kt
git commit -m "feat(reader): add ReadingSpeedTracker with EWMA session logic"
```

---

## Task 3: `ReadingSpeedStore` interface + impl + DI

**Files:**
- Create: `core/domain/src/main/kotlin/com/riffle/core/domain/ReadingSpeedStore.kt`
- Create: `core/data/src/main/kotlin/com/riffle/core/data/ReadingSpeedStoreImpl.kt`
- Create: `core/data/src/main/kotlin/com/riffle/core/data/di/ReadingSpeedDataStoreExt.kt`
- Create: `core/data/src/test/kotlin/com/riffle/core/data/ReadingSpeedStoreTest.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt`

- [ ] **Step 1: Write the failing store test**

```kotlin
package com.riffle.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.riffle.core.domain.ReadingSpeedTracker
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
class ReadingSpeedStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private fun buildStore() = ReadingSpeedStoreImpl(
        PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { tmp.newFile("reading_speed.preferences_pb") },
        )
    )

    @Test
    fun `default speed equals ReadingSpeedTracker DEFAULT_SECS_PER_POSITION`() = testScope.runTest {
        assertEquals(
            ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION,
            buildStore().speedSecPerPosition.first(),
            0.001,
        )
    }

    @Test
    fun `updateSpeed stores and re-reads the value`() = testScope.runTest {
        val store = buildStore()
        store.updateSpeed(50.0)
        assertEquals(50.0, store.speedSecPerPosition.first(), 0.001)
    }

    @Test
    fun `updated value persists across store instances`() {
        val file = tmp.newFile("reading_speed_round_trip.preferences_pb")
        val writeScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        runBlocking {
            ReadingSpeedStoreImpl(
                PreferenceDataStoreFactory.create(
                    scope = writeScope,
                    produceFile = { file },
                )
            ).updateSpeed(45.0)
        }
        writeScope.cancel()

        val readScope = CoroutineScope(UnconfinedTestDispatcher() + Job())
        val store2 = ReadingSpeedStoreImpl(
            PreferenceDataStoreFactory.create(
                scope = readScope,
                produceFile = { file },
            )
        )
        assertEquals(45.0, runBlocking { store2.speedSecPerPosition.first() }, 0.001)
        readScope.cancel()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:test --tests "com.riffle.core.data.ReadingSpeedStoreTest" 2>&1 | tail -20
```

Expected: FAIL with class not found.

- [ ] **Step 3: Create `ReadingSpeedStore` interface**

```kotlin
// core/domain/src/main/kotlin/com/riffle/core/domain/ReadingSpeedStore.kt
package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface ReadingSpeedStore {
    val speedSecPerPosition: Flow<Double>
    suspend fun updateSpeed(newSecPerPosition: Double)
}
```

- [ ] **Step 4: Create the DataStore extension**

```kotlin
// core/data/src/main/kotlin/com/riffle/core/data/di/ReadingSpeedDataStoreExt.kt
package com.riffle.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.readingSpeedDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "reading_speed_preferences")
```

- [ ] **Step 5: Create `ReadingSpeedStoreImpl`**

```kotlin
// core/data/src/main/kotlin/com/riffle/core/data/ReadingSpeedStoreImpl.kt
package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import com.riffle.core.data.di.ReadingSpeedDataStore
import com.riffle.core.domain.ReadingSpeedStore
import com.riffle.core.domain.ReadingSpeedTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ReadingSpeedStoreImpl @Inject constructor(
    @param:ReadingSpeedDataStore private val dataStore: DataStore<Preferences>,
) : ReadingSpeedStore {

    override val speedSecPerPosition: Flow<Double> = dataStore.data.map { prefs ->
        prefs[KEY_SECS_PER_POSITION] ?: ReadingSpeedTracker.DEFAULT_SECS_PER_POSITION
    }

    override suspend fun updateSpeed(newSecPerPosition: Double) {
        dataStore.edit { prefs -> prefs[KEY_SECS_PER_POSITION] = newSecPerPosition }
    }

    private companion object {
        val KEY_SECS_PER_POSITION = doublePreferencesKey("reading_speed_secs_per_position")
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
./gradlew :core:data:test --tests "com.riffle.core.data.ReadingSpeedStoreTest" 2>&1 | tail -10
```

Expected: all 3 PASS.

- [ ] **Step 7: Wire DI in `DataModule.kt`**

Add the Hilt qualifier annotation alongside the others (around line 148):
```kotlin
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReadingSpeedDataStore
```

Add the DataStore provider in the `@Module @InstallIn(SingletonComponent::class) object` block (alongside other DataStore providers, around line 620):
```kotlin
@Provides
@Singleton
@ReadingSpeedDataStore
fun provideReadingSpeedDataStore(
    @ApplicationContext context: Context
): DataStore<Preferences> = context.readingSpeedDataStore
```

Add the store binding in the `@Module @InstallIn(SingletonComponent::class) abstract class` section (alongside `bindWakeLockPreferencesStore`, around line 304):
```kotlin
@Binds
@Singleton
abstract fun bindReadingSpeedStore(impl: ReadingSpeedStoreImpl): ReadingSpeedStore
```

Add the imports:
```kotlin
import com.riffle.core.data.ReadingSpeedStoreImpl
import com.riffle.core.domain.ReadingSpeedStore
```

- [ ] **Step 8: Verify DI compiles**

```bash
./gradlew :core:data:compileDebugKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/ReadingSpeedStore.kt \
        core/data/src/main/kotlin/com/riffle/core/data/ReadingSpeedStoreImpl.kt \
        core/data/src/main/kotlin/com/riffle/core/data/di/ReadingSpeedDataStoreExt.kt \
        core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt \
        core/data/src/test/kotlin/com/riffle/core/data/ReadingSpeedStoreTest.kt
git commit -m "feat(reader): add ReadingSpeedStore with DataStore-backed adaptive speed"
```

---

## Task 4: `showReadingTimeEstimate` toggle — domain layer

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt`
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/BookFormattingOverrides.kt`
- Modify: `core/domain/src/test/kotlin/com/riffle/core/domain/BookFormattingOverridesTest.kt`

- [ ] **Step 1: Add to `FormattingPreferences`**

In `data class FormattingPreferences(...)`, add after `justifyText`:
```kotlin
val showReadingTimeEstimate: Boolean = DEFAULT_SHOW_READING_TIME_ESTIMATE,
```

In the `companion object`, add:
```kotlin
const val DEFAULT_SHOW_READING_TIME_ESTIMATE: Boolean = true
```

- [ ] **Step 2: Add to `BookFormattingOverrides`**

In `data class BookFormattingOverrides(...)`, add after `justifyText`:
```kotlin
val showReadingTimeEstimate: Boolean? = null,
```

In `isEmpty`, extend the last `&&` chain:
```kotlin
justifyText == null &&
showReadingTimeEstimate == null
```

In `applyTo(global)`, add after the `justifyText` line:
```kotlin
showReadingTimeEstimate = showReadingTimeEstimate ?: global.showReadingTimeEstimate,
```

In `withChanges(previous, new)`, add after the `justifyText` line:
```kotlin
showReadingTimeEstimate = if (new.showReadingTimeEstimate != previous.showReadingTimeEstimate) new.showReadingTimeEstimate else showReadingTimeEstimate,
```

- [ ] **Step 3: Write failing tests in `BookFormattingOverridesTest`**

Add these tests to `BookFormattingOverridesTest`:
```kotlin
@Test
fun `isEmpty is false when only showReadingTimeEstimate is set`() {
    assertFalse(BookFormattingOverrides(showReadingTimeEstimate = false).isEmpty)
}

@Test
fun `applyTo threads showReadingTimeEstimate override`() {
    val effective = BookFormattingOverrides(showReadingTimeEstimate = false).applyTo(global)
    assertFalse(effective.showReadingTimeEstimate)
    // Default is true, so null override → global default of true
    assertTrue(BookFormattingOverrides().applyTo(global).showReadingTimeEstimate)
}

@Test
fun `withChanges records showReadingTimeEstimate when it differs`() {
    val previous = global // showReadingTimeEstimate = true (default)
    val new = global.copy(showReadingTimeEstimate = false)
    val overrides = BookFormattingOverrides().withChanges(previous, new)
    assertEquals(false, overrides.showReadingTimeEstimate)
}
```

Note: the existing `global` val in the test has `showReadingTimeEstimate` default = true; you don't need to change it. The test accesses it via `.showReadingTimeEstimate` which will resolve to `true` after the field is added to `FormattingPreferences`.

- [ ] **Step 4: Run tests**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:domain:test --tests "com.riffle.core.domain.BookFormattingOverridesTest" 2>&1 | tail -10
```

Expected: all pass (existing + 3 new).

- [ ] **Step 5: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt \
        core/domain/src/main/kotlin/com/riffle/core/domain/BookFormattingOverrides.kt \
        core/domain/src/test/kotlin/com/riffle/core/domain/BookFormattingOverridesTest.kt
git commit -m "feat(reader): add showReadingTimeEstimate formatting toggle"
```

---

## Task 5: Room migration 35 → 36

**Files:**
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/BookFormattingPreferencesEntity.kt`
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt`
- Modify: `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt`

Per CLAUDE.md: bump version → build (exports schema JSON) → register migration → write test.

- [ ] **Step 1: Add column to `BookFormattingPreferencesEntity`**

Add after `justifyText`:
```kotlin
val showReadingTimeEstimate: Boolean? = null,
```

- [ ] **Step 2: Bump DB version and add migration in `RiffleDatabase.kt`**

Change `version = 35` → `version = 36`.

Add after `MIGRATION_34_35`:
```kotlin
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `book_formatting_preferences` ADD COLUMN `showReadingTimeEstimate` INTEGER DEFAULT NULL"
        )
    }
}
```

- [ ] **Step 3: Register migration in `DataModule.kt`**

In `addMigrations(...)`, add `RiffleDatabase.MIGRATION_35_36` to the list.

- [ ] **Step 4: Export the schema JSON**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:database:kspDebugKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. A new file `core/database/schemas/com.riffle.core.database.RiffleDatabase/36.json` is created.

- [ ] **Step 5: Write migration test in `MigrationTest.kt`**

Add before `migrateFullChain()`:
```kotlin
@Test
fun migration35To36_addsShowReadingTimeEstimateColumn() {
    helper.createDatabase(TEST_DB, 35).use { db ->
        db.execSQL(
            "INSERT INTO servers (id, url, isActive, insecureConnectionAllowed, username, serverType) " +
                "VALUES ('s1', 'http://media-server', 1, 0, 'test', 'AUDIOBOOKSHELF')"
        )
        db.execSQL(
            "INSERT INTO book_formatting_preferences " +
                "(serverId, itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, " +
                "showChapterMap, showReadingProgressLabels, showCurrentChapterLabel, doublePageSpread, justifyText) " +
                "VALUES ('s1', 'item1', 1.0, 'Light', 'Serif', 1.2, 1.0, 'Horizontal', 1, 0, 0, 0, 0)"
        )
    }

    val db = helper.runMigrationsAndValidate(TEST_DB, 36, true, RiffleDatabase.MIGRATION_35_36)

    db.query(
        "SELECT showReadingTimeEstimate FROM book_formatting_preferences WHERE itemId = 'item1'"
    ).use { c ->
        assertEquals(1, c.count)
        c.moveToFirst()
        assertTrue(c.isNull(0)) // NULL default — per-book not set
    }
}
```

In `migrateFullChain()`, update the final migration call:
```kotlin
// Change:
val db = helper.runMigrationsAndValidate(
    TEST_DB, 35, true,
    ...
    RiffleDatabase.MIGRATION_34_35,
)
// To:
val db = helper.runMigrationsAndValidate(
    TEST_DB, 36, true,
    ...
    RiffleDatabase.MIGRATION_34_35,
    RiffleDatabase.MIGRATION_35_36,
)
```

- [ ] **Step 6: Run migration tests**

Run per CLAUDE.md — the MigrationTest lives in `core:database` (not `:app`), so pin it to the Harness AVD manually:

```bash
# Boot the Harness Medium Phone AVD if not already running
# (see reference_api25_avd_boot_incantation.md in memory for incantation)
# Then with ANDROID_SERIAL set to that emulator:
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:database:connectedDebugAndroidTest \
  --tests "com.riffle.core.database.MigrationTest.migration35To36_addsShowReadingTimeEstimateColumn" \
  --tests "com.riffle.core.database.MigrationTest.migrateFullChain" 2>&1 | tail -20
```

Expected: both PASS.

- [ ] **Step 7: Commit**

```bash
git add core/database/src/main/kotlin/com/riffle/core/database/BookFormattingPreferencesEntity.kt \
        core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt \
        core/database/schemas/com.riffle.core.database.RiffleDatabase/36.json \
        core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt \
        core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt
git commit -m "feat(reader): Room migration 35→36 adds showReadingTimeEstimate column"
```

---

## Task 6: `FormattingPreferencesStoreImpl` — persist the new toggle

**Files:**
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/FormattingPreferencesStoreImpl.kt`

- [ ] **Step 1: Add the key and wire read/write**

In the `preferences: Flow<FormattingPreferences>` map block, add after `justifyText`:
```kotlin
showReadingTimeEstimate = prefs[KEY_SHOW_READING_TIME_ESTIMATE] ?: true,
```

In `update(preferences)` inside `dataStore.edit { prefs -> ... }`, add after `justifyText`:
```kotlin
prefs[KEY_SHOW_READING_TIME_ESTIMATE] = preferences.showReadingTimeEstimate
```

In the `companion object`, add:
```kotlin
val KEY_SHOW_READING_TIME_ESTIMATE = booleanPreferencesKey("show_reading_time_estimate")
```

- [ ] **Step 2: Verify it compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:data:compileDebugKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/riffle/core/data/FormattingPreferencesStoreImpl.kt
git commit -m "feat(reader): persist showReadingTimeEstimate in global formatting DataStore"
```

---

## Task 7: `EpubReaderViewModel` — session tracking + time remaining flows

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt`

- [ ] **Step 1: Add `_readaloudTrackFlow` alongside the existing `private var readaloudTrack`**

Find the line `private var readaloudTrack: com.riffle.core.domain.ReadaloudTrack? = null` (around line 339) and add directly below it:
```kotlin
private val _readaloudTrackFlow = MutableStateFlow<com.riffle.core.domain.ReadaloudTrack?>(null)
```

Find the method `getOrLoadReadaloudTrack` (around line 1713). After the line `readaloudTrack = track`, add:
```kotlin
_readaloudTrackFlow.value = track
```

- [ ] **Step 2: Inject `ReadingSpeedStore`**

In the `@HiltViewModel class EpubReaderViewModel @Inject constructor(...)`, add `ReadingSpeedStore` to the constructor (alphabetically with the other stores):
```kotlin
private val readingSpeedStore: ReadingSpeedStore,
```

Add the import:
```kotlin
import com.riffle.core.domain.ReadingSpeedStore
```

- [ ] **Step 3: Add session-tracking fields and hook into `onReaderResumed` / `onReaderClosed`**

Add two private fields near the other session-state fields (e.g., around `closeSyncDone`):
```kotlin
private var sessionStartProgression: Float? = null
private var sessionStartMs: Long = 0L
```

In `onReaderResumed()`, after the existing body, add:
```kotlin
// Snapshot reading position for adaptive speed tracking.
val totalPositions = railSegments.value.sumOf { it.weight.toDouble() }.toFloat()
if (totalPositions > 0f && _currentLocatorTotalProgression.value > 0f) {
    sessionStartProgression = _currentLocatorTotalProgression.value
    sessionStartMs = System.currentTimeMillis()
}
```

In `onReaderClosed()`, at the top of the method (before existing body), add:
```kotlin
flushReadingSession()
```

Add the private flush method (place it near the other private helpers):
```kotlin
private fun flushReadingSession() {
    val startProg = sessionStartProgression ?: return
    sessionStartProgression = null
    val timeDeltaSec = (System.currentTimeMillis() - sessionStartMs) / 1000.0
    val progressDelta = _currentLocatorTotalProgression.value - startProg
    if (progressDelta <= 0f) return
    val totalPositions = railSegments.value.sumOf { it.weight.toDouble() }.toFloat()
    viewModelScope.launch {
        val prior = readingSpeedStore.speedSecPerPosition.first()
        val updated = ReadingSpeedTracker.recordSession(
            progressDelta = progressDelta,
            timeDeltaSec = timeDeltaSec,
            totalPositions = totalPositions,
            priorSecPerPosition = prior,
        ) ?: return@launch
        readingSpeedStore.updateSpeed(updated)
    }
}
```

Add the import:
```kotlin
import com.riffle.core.domain.ReadingSpeedTracker
import kotlinx.coroutines.flow.first
```

- [ ] **Step 4: Add `chapterTimeRemaining` and `bookTimeRemaining` StateFlows**

Add after the existing `railCursorPosition` flow (around line 1115):

```kotlin
val chapterTimeRemaining: StateFlow<TimeRemaining?> = combine(
    combine(_readaloudTrackFlow, playbackState) { track, ps -> track to ps },
    combine(currentLocatorProgression, railSegments, activeRailSegmentIndex) { prog, segs, idx ->
        Triple(prog, segs, idx)
    },
    readingSpeedStore.speedSecPerPosition,
) { (track, ps), (chapterProg, segments, activeIdx), secsPerPos ->
    if (ps.connected && track != null) {
        val chapterEnd = track.chapterStartsSec.getOrNull(ps.currentChapterIndex + 1)
            ?: track.totalDurationSec
        val sec = ((chapterEnd - ps.positionGlobalSec) / ps.speed).toLong().coerceAtLeast(0L)
        TimeRemaining.Exact(sec)
    } else {
        val weight = segments.getOrNull(activeIdx)?.weight?.takeIf { it > 0f }
            ?: return@combine null
        TimeRemaining.Estimated(
            ((1f - chapterProg) * weight * secsPerPos).toLong().coerceAtLeast(0L)
        )
    }
}.stateIn(viewModelScope, SharingStarted.Eagerly, null)

val bookTimeRemaining: StateFlow<TimeRemaining?> = combine(
    combine(_readaloudTrackFlow, playbackState) { track, ps -> track to ps },
    combine(currentLocatorTotalProgression, railSegments) { prog, segs -> prog to segs },
    readingSpeedStore.speedSecPerPosition,
) { (track, ps), (totalProg, segments), secsPerPos ->
    if (ps.connected && track != null) {
        val sec = ((track.totalDurationSec - ps.positionGlobalSec) / ps.speed)
            .toLong().coerceAtLeast(0L)
        TimeRemaining.Exact(sec)
    } else {
        if (totalProg <= 0f) return@combine null
        val totalPositions = segments.sumOf { it.weight.toDouble() }
        if (totalPositions <= 0.0) return@combine null
        TimeRemaining.Estimated(
            ((1.0 - totalProg) * totalPositions * secsPerPos).toLong().coerceAtLeast(0L)
        )
    }
}.stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

Add the import:
```kotlin
import com.riffle.core.domain.TimeRemaining
```

- [ ] **Step 5: Verify it compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run all JVM tests to catch regressions**

```bash
./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, no new failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt
git commit -m "feat(reader): adaptive session tracking and time remaining flows in ViewModel"
```

---

## Task 8: `FormattingPanel` — "Time remaining" toggle

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt`

- [ ] **Step 1: Add the toggle row after the `showCurrentChapterLabel` block**

Find this block (around line 353):
```kotlin
Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
) {
    Text(
        "Current chapter label",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.weight(1f),
    )
    Switch(
        checked = prefs.showCurrentChapterLabel,
        onCheckedChange = { onPrefsChange(prefs.copy(showCurrentChapterLabel = it)) },
    )
}
```

Add immediately after it:
```kotlin
Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
) {
    Text(
        "Time remaining",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.weight(1f),
    )
    Switch(
        checked = prefs.showReadingTimeEstimate,
        onCheckedChange = { onPrefsChange(prefs.copy(showReadingTimeEstimate = it)) },
    )
}
```

- [ ] **Step 2: Verify it compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt
git commit -m "feat(reader): add time remaining toggle to formatting panel"
```

---

## Task 9: `EpubReaderScreen` — the pill UI

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt`

- [ ] **Step 1: Update the visibility guard (line ~372)**

Find:
```kotlin
formattingPrefs.showReadingProgressLabels ||
formattingPrefs.showCurrentChapterLabel
```

Replace with:
```kotlin
formattingPrefs.showReadingProgressLabels ||
formattingPrefs.showCurrentChapterLabel ||
formattingPrefs.showReadingTimeEstimate
```

- [ ] **Step 2: Collect the new time flows and pass them to `EpubChapterRailOverlay`**

In the main composable body where the `formattingPrefs` is collected (around line 161), add:
```kotlin
val chapterTimeRemaining by viewModel.chapterTimeRemaining.collectAsState()
val bookTimeRemaining by viewModel.bookTimeRemaining.collectAsState()
```

Find the `EpubChapterRailOverlay(...)` call (around line 426) and add the two new parameters:
```kotlin
EpubChapterRailOverlay(
    viewModel = viewModel,
    modifier = ...,
    showProgressLabels = formattingPrefs.showReadingProgressLabels,
    showChapterNameLabel = formattingPrefs.showCurrentChapterLabel,
    showReadingTimeEstimate = formattingPrefs.showReadingTimeEstimate,   // new
    chapterTimeRemaining = chapterTimeRemaining,                          // new
    bookTimeRemaining = bookTimeRemaining,                                // new
    ...
)
```

- [ ] **Step 3: Add parameters to `EpubChapterRailOverlay`**

Find `private fun EpubChapterRailOverlay(` (around line 548) and add the three new parameters:
```kotlin
private fun EpubChapterRailOverlay(
    viewModel: EpubReaderViewModel,
    modifier: Modifier = Modifier,
    showProgressLabels: Boolean,
    showChapterNameLabel: Boolean,
    showReadingTimeEstimate: Boolean,       // new
    chapterTimeRemaining: TimeRemaining?,   // new
    bookTimeRemaining: TimeRemaining?,      // new
    ...
)
```

Add import:
```kotlin
import com.riffle.core.domain.TimeRemaining
```

Pass them through to `ReadingProgressLabels(...)`:
```kotlin
ReadingProgressLabels(
    activeChapterIndex = activeRailSegmentIndex,
    chapterCount = railSegments.size,
    activeChapterTitle = railSegments.getOrNull(activeRailSegmentIndex)?.title.orEmpty(),
    totalProgress = ...,
    readerTheme = ...,
    showCountAndPercent = showProgressLabels,
    showChapterName = showChapterNameLabel,
    showReadingTimeEstimate = showReadingTimeEstimate,   // new
    chapterTimeRemaining = chapterTimeRemaining,          // new
    bookTimeRemaining = bookTimeRemaining,                // new
)
```

- [ ] **Step 4: Update `ReadingProgressLabels` to render the pill**

Find `private fun ReadingProgressLabels(` (around line 619) and add the three new parameters:
```kotlin
private fun ReadingProgressLabels(
    activeChapterIndex: Int,
    chapterCount: Int,
    activeChapterTitle: String,
    totalProgress: Float,
    readerTheme: ReaderTheme,
    showCountAndPercent: Boolean,
    showChapterName: Boolean,
    showReadingTimeEstimate: Boolean,       // new
    chapterTimeRemaining: TimeRemaining?,   // new
    bookTimeRemaining: TimeRemaining?,      // new
)
```

In the function body, change the centre slot from a plain `Text` to a `Column`:

Replace:
```kotlin
if (showChapterName) {
    Text(
        text = activeChapterTitle,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        textAlign = TextAlign.Center,
        fontStyle = FontStyle.Italic,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .weight(2f)
            .testTag("reading_progress_chapter_name")
            .semantics { contentDescription = "Current chapter: $activeChapterTitle" },
    )
}
```

With:
```kotlin
if (showChapterName || showReadingTimeEstimate) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.weight(2f),
    ) {
        if (showChapterName) {
            Text(
                text = activeChapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                textAlign = TextAlign.Center,
                fontStyle = FontStyle.Italic,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .testTag("reading_progress_chapter_name")
                    .semantics { contentDescription = "Current chapter: $activeChapterTitle" },
            )
        }
        if (showReadingTimeEstimate && chapterTimeRemaining != null && bookTimeRemaining != null) {
            val isExact = chapterTimeRemaining is TimeRemaining.Exact
            val pillColor = if (isExact) MaterialTheme.colorScheme.tertiary else textColor
            val pillBgColor = if (isExact) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                              else textColor.copy(alpha = 0.08f)
            val pillText = "${chapterTimeRemaining.formatChapter()} · ${bookTimeRemaining.formatBook()}"
            Text(
                text = pillText,
                style = MaterialTheme.typography.labelSmall,
                color = pillColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .background(color = pillBgColor, shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("reading_time_remaining")
                    .semantics { contentDescription = "Time remaining: $pillText" },
            )
        }
    }
}
```

- [ ] **Step 5: Add the formatting helpers**

Add these two private extension functions at the bottom of the file (or just below `ReadingProgressLabels`):

```kotlin
private fun TimeRemaining.formatChapter(): String = formatDuration(sec, suffix = "in chapter")

private fun TimeRemaining.formatBook(): String = formatDuration(sec, suffix = "left")

private fun TimeRemaining.formatDuration(sec: Long, suffix: String): String {
    val hours = sec / 3600
    val minutes = (sec % 3600) / 60
    return when (this) {
        is TimeRemaining.Estimated -> {
            val time = when {
                sec < 60 -> "< 1 min"
                hours > 0 -> "~${hours}h ${minutes}m"
                else -> "~${minutes} min"
            }
            "$time $suffix"
        }
        is TimeRemaining.Exact -> {
            val time = if (hours > 0) "%d:%02d:%02d".format(hours, minutes, sec % 60)
                       else "%d:%02d".format(minutes, sec % 60)
            "$time $suffix"
        }
    }
}
```

Add any missing imports:
```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import com.riffle.core.domain.TimeRemaining
```

- [ ] **Step 6: Verify full build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:compileDebugKotlin 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL with no errors.

- [ ] **Step 7: Run all JVM tests**

```bash
./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, no new failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt
git commit -m "feat(reader): reading time remaining pill in chapter label area"
```

---

## Self-review checklist

- [x] `TimeRemaining` sealed class → Task 1
- [x] `ReadingSpeedTracker` pure EWMA + tests → Task 2
- [x] `ReadingSpeedStore` interface + impl + DataStore ext + DI → Task 3
- [x] `showReadingTimeEstimate` in `FormattingPreferences` + `BookFormattingOverrides` + test → Task 4
- [x] Room migration 35→36 + schema JSON + `MigrationTest` → Task 5
- [x] `FormattingPreferencesStoreImpl` key → Task 6
- [x] ViewModel: `_readaloudTrackFlow`, session tracking, `chapterTimeRemaining` / `bookTimeRemaining` → Task 7
- [x] `FormattingPanel` toggle → Task 8
- [x] UI pill with estimated/exact formatting → Task 9
- [x] Visibility guard updated → Task 9 Step 1
- [x] Exact mode uses `MaterialTheme.colorScheme.tertiary` → Task 9 Step 4
- [x] Global DataStore key for toggle → Task 6
- [x] Per-book Room column for toggle → Task 5
- [x] `ReadingSpeedStore` on its own DataStore (not FormattingPreferences) → Task 3

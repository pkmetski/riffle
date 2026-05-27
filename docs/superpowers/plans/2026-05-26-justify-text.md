# Justify Text Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Justify text" toggle (default on) to EPUB Formatting Preferences, stored in both the global DataStore and per-book Room override.

**Architecture:** `justifyText: Boolean` is added to the existing `FormattingPreferences` domain model; it flows through the same two-tier persistence stack (global `FormattingPreferencesStoreImpl` + per-book `BookFormattingPreferencesStoreImpl`) and is mapped to Readium's `textAlign` field (`JUSTIFY` when on, `START` when off). The UI toggle is inserted into `FormattingPanel` after the Font Family row. A Room migration bumps the schema from v14 to v15.

**Tech Stack:** Kotlin, Jetpack Compose, Room (SQLite), Jetpack DataStore Preferences, Readium Kotlin SDK 3.0.0

---

## File Map

| File | Change |
|---|---|
| `core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt` | Add `justifyText: Boolean = true` |
| `core/database/src/main/kotlin/com/riffle/core/database/BookFormattingPreferencesEntity.kt` | Add `justifyText: Boolean = true` column |
| `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt` | Bump version 14→15, add `MIGRATION_14_15` |
| `core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt` | Register `MIGRATION_14_15` |
| `core/data/src/main/kotlin/com/riffle/core/data/FormattingPreferencesStoreImpl.kt` | Add `KEY_JUSTIFY_TEXT` DataStore key |
| `core/data/src/main/kotlin/com/riffle/core/data/BookFormattingPreferencesStoreImpl.kt` | Map `justifyText` in load/save |
| `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapper.kt` | Add `textAlign` mapping |
| `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt` | Add toggle after Font Family row |
| `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt` | Add `migration14To15()` + update `migrateFullChain` |
| `app/src/androidTest/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapperTest.kt` | Add mapper tests |
| `core/data/src/test/kotlin/com/riffle/core/data/FormattingPreferencesStoreTest.kt` | Add DataStore test |

---

## Task 1: Add `justifyText` to the domain model

**Files:**
- Modify: `core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt`

- [ ] **Step 1: Add the field**

Replace the data class body with:

```kotlin
data class FormattingPreferences(
    val fontSize: Float = 1.0f,
    val theme: ReaderTheme = ReaderTheme.Light,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.Serif,
    val lineSpacing: Float = 1.2f,
    val margins: Float = 1.0f,
    val orientation: ReaderOrientation = ReaderOrientation.Horizontal,
    val showChapterMap: Boolean = true,
    val doublePageSpread: Boolean = false,
    val justifyText: Boolean = true,
)
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :core:domain:assemble
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add core/domain/src/main/kotlin/com/riffle/core/domain/FormattingPreferences.kt
git commit -m "feat(domain): add justifyText field to FormattingPreferences (default true)"
```

---

## Task 2: Mapper — tests then implementation

**Files:**
- Modify: `app/src/androidTest/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapperTest.kt`
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapper.kt`

- [ ] **Step 1: Write the failing tests**

Add these two tests to `FormattingPreferencesMapperTest` (inside the class, after the existing tests):

```kotlin
@Test
fun justifyTextTrueMapsToTextAlignJustify() {
    val result = FormattingPreferences(justifyText = true).toEpubPreferences()
    assertEquals(org.readium.r2.navigator.preferences.TextAlign.JUSTIFY, result.textAlign)
}

@Test
fun justifyTextFalseMapsToTextAlignStart() {
    val result = FormattingPreferences(justifyText = false).toEpubPreferences()
    assertEquals(org.readium.r2.navigator.preferences.TextAlign.START, result.textAlign)
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:connectedDebugAndroidTest --tests "com.riffle.app.feature.reader.FormattingPreferencesMapperTest.justifyTextTrueMapsToTextAlignJustify" --tests "com.riffle.app.feature.reader.FormattingPreferencesMapperTest.justifyTextFalseMapsToTextAlignStart"
```

Expected: both FAIL with `expected <JUSTIFY> but was <null>` (or similar).

> **Note:** Run this on the Harness Medium Phone AVD via `make harness-test` if you need the full suite, but individual test targeting via `--tests` is fine for quick feedback here.

- [ ] **Step 3: Implement the mapping**

In `FormattingPreferencesMapper.kt`, add the `TextAlign` import and the new field to `EpubPreferences(...)`:

```kotlin
@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme

fun FormattingPreferences.toEpubPreferences(isLandscape: Boolean = false): EpubPreferences = EpubPreferences(
    fontSize = fontSize.toDouble(),
    theme = when (theme) {
        ReaderTheme.Light -> Theme.LIGHT
        ReaderTheme.Dark -> Theme.DARK
        ReaderTheme.Sepia -> Theme.SEPIA
    },
    fontFamily = when (fontFamily) {
        ReaderFontFamily.Serif -> FontFamily("serif")
        ReaderFontFamily.SansSerif -> FontFamily("sans-serif")
        ReaderFontFamily.Monospace -> FontFamily("monospace")
        ReaderFontFamily.Literata -> FontFamily("Literata")
        ReaderFontFamily.Merriweather -> FontFamily("Merriweather")
        ReaderFontFamily.OpenDyslexic -> FontFamily("OpenDyslexic")
    },
    textAlign = if (justifyText) TextAlign.JUSTIFY else TextAlign.START,
    // lineHeight only takes effect when publisherStyles is off
    publisherStyles = false,
    lineHeight = lineSpacing.toDouble(),
    pageMargins = margins.toDouble(),
    scroll = orientation == ReaderOrientation.Vertical,
    columnCount = if (orientation != ReaderOrientation.Vertical && doublePageSpread && isLandscape) ColumnCount.TWO else ColumnCount.ONE,
)
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:connectedDebugAndroidTest --tests "com.riffle.app.feature.reader.FormattingPreferencesMapperTest.justifyTextTrueMapsToTextAlignJustify" --tests "com.riffle.app.feature.reader.FormattingPreferencesMapperTest.justifyTextFalseMapsToTextAlignStart"
```

Expected: both PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapperTest.kt
git add app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPreferencesMapper.kt
git commit -m "feat(reader): map justifyText to Readium TextAlign in EPUB preferences"
```

---

## Task 3: Global DataStore — test then implementation

**Files:**
- Modify: `core/data/src/test/kotlin/com/riffle/core/data/FormattingPreferencesStoreTest.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/FormattingPreferencesStoreImpl.kt`

- [ ] **Step 1: Write the failing test**

Add this test to `FormattingPreferencesStoreTest` (inside the class, after existing tests):

```kotlin
@Test
fun `saved justifyText is returned after update`() = testScope.runTest {
    val store = buildStore()
    store.update(FormattingPreferences(justifyText = false))
    assertEquals(false, store.preferences.first().justifyText)
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :core:data:test --tests "com.riffle.core.data.FormattingPreferencesStoreTest.saved justifyText is returned after update"
```

Expected: FAIL — `justifyText` is not yet persisted so `true` (the default) comes back instead of `false`.

- [ ] **Step 3: Implement the DataStore change**

Replace `FormattingPreferencesStoreImpl.kt` with:

```kotlin
package com.riffle.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.riffle.core.data.di.FormattingPreferencesDataStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class FormattingPreferencesStoreImpl @Inject constructor(
    @param:FormattingPreferencesDataStore private val dataStore: DataStore<Preferences>,
) : FormattingPreferencesStore {

    override val preferences: Flow<FormattingPreferences> = dataStore.data.map { prefs ->
        FormattingPreferences(
            fontSize = prefs[KEY_FONT_SIZE] ?: 1.0f,
            theme = prefs[KEY_THEME]
                ?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() }
                ?: ReaderTheme.Light,
            fontFamily = prefs[KEY_FONT_FAMILY]
                ?.let { runCatching { ReaderFontFamily.valueOf(it) }.getOrNull() }
                ?: ReaderFontFamily.Serif,
            lineSpacing = prefs[KEY_LINE_SPACING] ?: 1.2f,
            margins = prefs[KEY_MARGINS] ?: 1.0f,
            orientation = prefs[KEY_ORIENTATION]
                ?.let { runCatching { ReaderOrientation.valueOf(it) }.getOrNull() }
                ?: ReaderOrientation.Horizontal,
            showChapterMap = prefs[KEY_SHOW_CHAPTER_MAP] ?: true,
            doublePageSpread = prefs[KEY_DOUBLE_PAGE_SPREAD] ?: false,
            justifyText = prefs[KEY_JUSTIFY_TEXT] ?: true,
        )
    }

    override suspend fun update(preferences: FormattingPreferences) {
        dataStore.edit { prefs ->
            prefs[KEY_FONT_SIZE] = preferences.fontSize
            prefs[KEY_THEME] = preferences.theme.name
            prefs[KEY_FONT_FAMILY] = preferences.fontFamily.name
            prefs[KEY_LINE_SPACING] = preferences.lineSpacing
            prefs[KEY_MARGINS] = preferences.margins
            prefs[KEY_ORIENTATION] = preferences.orientation.name
            prefs[KEY_SHOW_CHAPTER_MAP] = preferences.showChapterMap
            prefs[KEY_DOUBLE_PAGE_SPREAD] = preferences.doublePageSpread
            prefs[KEY_JUSTIFY_TEXT] = preferences.justifyText
        }
    }

    private companion object {
        val KEY_FONT_SIZE = floatPreferencesKey("font_size")
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_FONT_FAMILY = stringPreferencesKey("font_family")
        val KEY_LINE_SPACING = floatPreferencesKey("line_spacing")
        val KEY_MARGINS = floatPreferencesKey("margins")
        val KEY_ORIENTATION = stringPreferencesKey("orientation")
        val KEY_SHOW_CHAPTER_MAP = booleanPreferencesKey("show_chapter_map")
        val KEY_DOUBLE_PAGE_SPREAD = booleanPreferencesKey("double_page_spread")
        val KEY_JUSTIFY_TEXT = booleanPreferencesKey("justify_text")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :core:data:test --tests "com.riffle.core.data.FormattingPreferencesStoreTest.saved justifyText is returned after update"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/test/kotlin/com/riffle/core/data/FormattingPreferencesStoreTest.kt
git add core/data/src/main/kotlin/com/riffle/core/data/FormattingPreferencesStoreImpl.kt
git commit -m "feat(data): persist justifyText in global formatting DataStore"
```

---

## Task 4: Room entity + database migration + per-book store

**Files:**
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/BookFormattingPreferencesEntity.kt`
- Modify: `core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt`
- Modify: `core/data/src/main/kotlin/com/riffle/core/data/BookFormattingPreferencesStoreImpl.kt`
- Modify: `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt`

- [ ] **Step 1: Write the failing migration test**

Add `migration14To15()` to `MigrationTest` (after `migration13To14()`):

```kotlin
@Test
fun migration14To15() {
    helper.createDatabase(TEST_DB, 14).use { db ->
        db.execSQL(
            "INSERT INTO book_formatting_preferences (itemId, fontSize, theme, fontFamily, lineSpacing, margins, orientation, showChapterMap, doublePageSpread) " +
                "VALUES ('item1', 1.0, 'Light', 'Serif', 1.2, 1.0, 'Horizontal', 1, 0)"
        )
    }

    val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, RiffleDatabase.MIGRATION_14_15)

    db.query("SELECT itemId, justifyText FROM book_formatting_preferences WHERE itemId = 'item1'").use { cursor ->
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals("item1", cursor.getString(0))
        assertEquals(1, cursor.getInt(1)) // justifyText defaults to 1 (true)
    }
}
```

Also update `migrateFullChain` — replace the `helper.runMigrationsAndValidate(TEST_DB, 14, ...)` call with:

```kotlin
val db = helper.runMigrationsAndValidate(
    TEST_DB, 15, true,
    RiffleDatabase.MIGRATION_1_2,
    RiffleDatabase.MIGRATION_2_3,
    RiffleDatabase.MIGRATION_3_4,
    RiffleDatabase.MIGRATION_4_5,
    RiffleDatabase.MIGRATION_5_6,
    RiffleDatabase.MIGRATION_6_7,
    RiffleDatabase.MIGRATION_7_8,
    RiffleDatabase.MIGRATION_8_9,
    RiffleDatabase.MIGRATION_9_10,
    RiffleDatabase.MIGRATION_10_11,
    RiffleDatabase.MIGRATION_11_12,
    RiffleDatabase.MIGRATION_12_13,
    RiffleDatabase.MIGRATION_13_14,
    RiffleDatabase.MIGRATION_14_15,
)
```

- [ ] **Step 2: Add `justifyText` to the Room entity**

Replace `BookFormattingPreferencesEntity.kt` with:

```kotlin
package com.riffle.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "book_formatting_preferences")
data class BookFormattingPreferencesEntity(
    @PrimaryKey val itemId: String,
    val fontSize: Float,
    val theme: String,
    val fontFamily: String,
    val lineSpacing: Float,
    val margins: Float,
    val orientation: String,
    val showChapterMap: Boolean = true,
    val doublePageSpread: Boolean = false,
    val justifyText: Boolean = true,
)
```

- [ ] **Step 3: Bump the DB version and add the migration**

In `RiffleDatabase.kt`:
- Change `version = 14` to `version = 15`
- Add `MIGRATION_14_15` to the companion object (after `MIGRATION_13_14`):

```kotlin
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `book_formatting_preferences` ADD COLUMN `justifyText` INTEGER NOT NULL DEFAULT 1")
    }
}
```

- [ ] **Step 4: Build the project to export the new schema JSON**

```bash
./gradlew :core:database:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. This writes `core/database/schemas/com.riffle.core.database.RiffleDatabase/15.json`.

- [ ] **Step 5: Register the migration in DatabaseModule**

In `core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt`, add `RiffleDatabase.MIGRATION_14_15,` after `MIGRATION_13_14`:

```kotlin
.addMigrations(
    RiffleDatabase.MIGRATION_1_2,
    RiffleDatabase.MIGRATION_2_3,
    RiffleDatabase.MIGRATION_3_4,
    RiffleDatabase.MIGRATION_4_5,
    RiffleDatabase.MIGRATION_5_6,
    RiffleDatabase.MIGRATION_6_7,
    RiffleDatabase.MIGRATION_7_8,
    RiffleDatabase.MIGRATION_8_9,
    RiffleDatabase.MIGRATION_9_10,
    RiffleDatabase.MIGRATION_10_11,
    RiffleDatabase.MIGRATION_11_12,
    RiffleDatabase.MIGRATION_12_13,
    RiffleDatabase.MIGRATION_13_14,
    RiffleDatabase.MIGRATION_14_15,
)
```

- [ ] **Step 6: Wire `justifyText` into `BookFormattingPreferencesStoreImpl`**

Replace `BookFormattingPreferencesStoreImpl.kt` with:

```kotlin
package com.riffle.core.data

import com.riffle.core.database.BookFormattingPreferencesDao
import com.riffle.core.database.BookFormattingPreferencesEntity
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import javax.inject.Inject

class BookFormattingPreferencesStoreImpl @Inject constructor(
    private val dao: BookFormattingPreferencesDao,
) : BookFormattingPreferencesStore {

    override suspend fun load(itemId: String): FormattingPreferences? {
        val entity = dao.getByItemId(itemId) ?: return null
        return FormattingPreferences(
            fontSize = entity.fontSize,
            theme = runCatching { ReaderTheme.valueOf(entity.theme) }.getOrDefault(ReaderTheme.Light),
            fontFamily = runCatching { ReaderFontFamily.valueOf(entity.fontFamily) }.getOrDefault(ReaderFontFamily.Serif),
            lineSpacing = entity.lineSpacing,
            margins = entity.margins,
            orientation = runCatching { ReaderOrientation.valueOf(entity.orientation) }.getOrDefault(ReaderOrientation.Horizontal),
            showChapterMap = entity.showChapterMap,
            doublePageSpread = entity.doublePageSpread,
            justifyText = entity.justifyText,
        )
    }

    override suspend fun save(itemId: String, preferences: FormattingPreferences) {
        dao.upsert(
            BookFormattingPreferencesEntity(
                itemId = itemId,
                fontSize = preferences.fontSize,
                theme = preferences.theme.name,
                fontFamily = preferences.fontFamily.name,
                lineSpacing = preferences.lineSpacing,
                margins = preferences.margins,
                orientation = preferences.orientation.name,
                showChapterMap = preferences.showChapterMap,
                doublePageSpread = preferences.doublePageSpread,
                justifyText = preferences.justifyText,
            )
        )
    }

    override suspend fun clear(itemId: String) {
        dao.deleteByItemId(itemId)
    }
}
```

- [ ] **Step 7: Run the migration tests**

```bash
make harness-test
```

Expected: `migration14To15` and `migrateFullChain` both PASS.

- [ ] **Step 8: Commit**

```bash
git add core/database/src/main/kotlin/com/riffle/core/database/BookFormattingPreferencesEntity.kt
git add core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt
git add "core/database/schemas/com.riffle.core.database.RiffleDatabase/15.json"
git add core/data/src/main/kotlin/com/riffle/core/data/di/DatabaseModule.kt
git add core/data/src/main/kotlin/com/riffle/core/data/BookFormattingPreferencesStoreImpl.kt
git add core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt
git commit -m "feat(database): add justifyText column to book_formatting_preferences (migration 14→15)"
```

---

## Task 5: UI toggle in FormattingPanel

**Files:**
- Modify: `app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt`

- [ ] **Step 1: Add the toggle after the Font Family row**

In `FormattingPanel.kt`, insert the following block between the Font Family section and the `Spacer(Modifier.height(16.dp))` before "Line spacing":

```kotlin
            Spacer(Modifier.height(16.dp))

            // Justify text toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Justify text",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = prefs.justifyText,
                    onCheckedChange = { onPrefsChange(prefs.copy(justifyText = it)) },
                )
            }
```

The resulting order in the panel will be: Font Size → Theme → Font Family → **Justify text** → Line Spacing → Margins → Reading Orientation → …

- [ ] **Step 2: Build to verify there are no compile errors**

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/riffle/app/feature/reader/FormattingPanel.kt
git commit -m "feat(reader): add Justify text toggle to FormattingPanel"
```

---

## Task 6: Final verification

- [ ] **Step 1: Run the full test suite**

```bash
make harness-test
```

Expected: all tests PASS (migration14To15, migrateFullChain, both mapper tests, DataStore test).

- [ ] **Step 2: Update README.md**

In `README.md`, find the Features list entry for issue #106 and change `- [ ]` to `- [x]`.

- [ ] **Step 3: Final commit**

```bash
git add README.md
git commit -m "chore: mark justify text feature complete in README"
```

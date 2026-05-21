# Migration Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `MigrationTestHelper`-based instrumented tests covering all four `RiffleDatabase` migrations, asserting that schema changes apply correctly and pre-existing data survives each migration.

**Architecture:** Backfill the missing `1.json` schema export, wire `room-testing` into the `core/database` androidTest build, create a single `MigrationTest.kt` with one test per migration step plus a full-chain test, and document the convention in `CLAUDE.md`.

**Tech Stack:** Room 2.8.4 · `MigrationTestHelper` · JUnit 4 · AndroidX Test · Kotlin

---

## File map

| Action | File |
|---|---|
| Create | `core/database/schemas/com.riffle.core.database.RiffleDatabase/1.json` |
| Modify | `gradle/libs.versions.toml` |
| Modify | `core/database/build.gradle.kts` |
| Create | `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt` |
| Modify | `CLAUDE.md` |

---

## Task 1: Backfill the v1 schema JSON

Version 1 contained only the `servers` table — confirmed by `MIGRATION_1_2` which creates both `libraries` and `library_items`. The `servers` DDL is identical across all versions (no migration ever touched it).

The `identityHash` field in a Room schema JSON is checked only structurally by `MigrationTestHelper.runMigrationsAndValidate` — Room's framework overwrites `room_master_table` with the target version's hash during migration. The v1 hash is therefore a placeholder.

**Files:**
- Create: `core/database/schemas/com.riffle.core.database.RiffleDatabase/1.json`

- [ ] **Create `1.json`**

```json
{
  "formatVersion": 1,
  "database": {
    "version": 1,
    "identityHash": "d6b0b6a4c8e4a4a1b3c2d5e8f9a7b6c5",
    "entities": [
      {
        "tableName": "servers",
        "createSql": "CREATE TABLE IF NOT EXISTS `${TABLE_NAME}` (`id` TEXT NOT NULL, `url` TEXT NOT NULL, `displayName` TEXT NOT NULL, `isActive` INTEGER NOT NULL, `insecureConnectionAllowed` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "fields": [
          {
            "fieldPath": "id",
            "columnName": "id",
            "affinity": "TEXT",
            "notNull": true
          },
          {
            "fieldPath": "url",
            "columnName": "url",
            "affinity": "TEXT",
            "notNull": true
          },
          {
            "fieldPath": "displayName",
            "columnName": "displayName",
            "affinity": "TEXT",
            "notNull": true
          },
          {
            "fieldPath": "isActive",
            "columnName": "isActive",
            "affinity": "INTEGER",
            "notNull": true
          },
          {
            "fieldPath": "insecureConnectionAllowed",
            "columnName": "insecureConnectionAllowed",
            "affinity": "INTEGER",
            "notNull": true
          }
        ],
        "primaryKey": {
          "autoGenerate": false,
          "columnNames": [
            "id"
          ]
        },
        "indices": [],
        "foreignKeys": []
      }
    ],
    "views": [],
    "setupQueries": [
      "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
      "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'd6b0b6a4c8e4a4a1b3c2d5e8f9a7b6c5')"
    ]
  }
}
```

- [ ] **Commit**

```bash
git add core/database/schemas/com.riffle.core.database.RiffleDatabase/1.json
git commit -m "chore: backfill v1 schema JSON for migration testing"
```

---

## Task 2: Add test dependencies

`room-testing` is not in the version catalog. `androidx-test-runner` is also missing. `androidx-junit` already exists (line 66 of `libs.versions.toml`) and maps to `androidx.test.ext:junit:1.2.1`, so it does not need adding.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `core/database/build.gradle.kts`

- [ ] **Add library entries to `gradle/libs.versions.toml`**

Insert after line 40 (the `androidx-room-compiler` line):

```toml
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
androidx-test-runner = { group = "androidx.test", name = "runner", version = "1.6.2" }
```

- [ ] **Update `core/database/build.gradle.kts`**

Replace the entire file with:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.riffle.core.database"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("androidTest").assets.srcDirs += files("$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
```

The `sourceSets` block makes the `schemas/` directory available as androidTest assets, which is required for `MigrationTestHelper` to locate the schema JSON files by version number.

- [ ] **Commit**

```bash
git add gradle/libs.versions.toml core/database/build.gradle.kts
git commit -m "chore(database): add room-testing androidTest dependencies"
```

---

## Task 3: Write the migration tests

**Files:**
- Create: `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt`

The pattern for every test is:
1. `helper.createDatabase(TEST_DB, N)` — opens a real SQLite DB at schema N using the exported JSON
2. Insert rows via raw `execSQL` (no DAOs — they depend on the current entity model)
3. `helper.runMigrationsAndValidate(TEST_DB, N+1, true, MIGRATION_N_(N+1))` — runs the migration and validates the resulting schema against the exported JSON for version N+1
4. Query with a raw `Cursor` and assert column values

- [ ] **Create `MigrationTest.kt`**

```kotlin
package com.riffle.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test"

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RiffleDatabase::class.java,
    )

    @Test
    fun migration1To2() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed) VALUES ('s1', 'http://localhost', 'My Server', 1, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, RiffleDatabase.MIGRATION_1_2)

        db.query("SELECT url, displayName FROM servers WHERE id = 's1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("http://localhost", cursor.getString(0))
            assertEquals("My Server", cursor.getString(1))
        }
        db.query("SELECT COUNT(*) FROM libraries").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM library_items").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration2To3() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO libraries (id, name, mediaType, serverId) VALUES ('lib1', 'Books', 'book', 's1')"
            )
            db.execSQL(
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, isDownloaded) VALUES ('item1', 'lib1', 'Dune', 'Herbert', NULL, 0.5, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, RiffleDatabase.MIGRATION_2_3)

        db.query("SELECT id, name, mediaType, serverId, isUnsupported FROM libraries WHERE id = 'lib1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("lib1", cursor.getString(0))
            assertEquals("Books", cursor.getString(1))
            assertEquals("book", cursor.getString(2))
            assertEquals("s1", cursor.getString(3))
            assertEquals(0, cursor.getInt(4)) // isUnsupported defaults to 0
        }
        db.query("SELECT id, title, author, readingProgress, isDownloaded, isSupported FROM library_items WHERE id = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals("Dune", cursor.getString(1))
            assertEquals("Herbert", cursor.getString(2))
            assertEquals(0.5, cursor.getDouble(3), 0.001)
            assertEquals(0, cursor.getInt(4))
            assertEquals(1, cursor.getInt(5)) // isSupported defaults to 1
        }
    }

    @Test
    fun migration3To4() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO libraries (id, name, mediaType, serverId, isUnsupported) VALUES ('lib1', 'Books', 'book', 's1', 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, RiffleDatabase.MIGRATION_3_4)

        db.query("SELECT id FROM libraries WHERE id = 'lib1'").use { cursor ->
            assertEquals(1, cursor.count)
        }
        db.query("SELECT COUNT(*) FROM series").use { cursor ->
            cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM collections").use { cursor ->
            cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM series_items").use { cursor ->
            cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM collection_items").use { cursor ->
            cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration4To5() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO library_items (id, libraryId, title, author, coverUrl, readingProgress, isDownloaded, isSupported) VALUES ('item1', 'lib1', 'Dune', 'Herbert', NULL, 0.25, 0, 1)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, RiffleDatabase.MIGRATION_4_5)

        db.query("SELECT id, title, author, readingProgress, isDownloaded, isSupported, ebookFileIno FROM library_items WHERE id = 'item1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("item1", cursor.getString(0))
            assertEquals("Dune", cursor.getString(1))
            assertEquals("Herbert", cursor.getString(2))
            assertEquals(0.25, cursor.getDouble(3), 0.001)
            assertEquals(0, cursor.getInt(4))
            assertEquals(1, cursor.getInt(5))
            assertNull(cursor.getString(6)) // ebookFileIno defaults to NULL
        }
        db.query("SELECT COUNT(*) FROM reading_positions").use { cursor ->
            cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migrateFullChain() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, url, displayName, isActive, insecureConnectionAllowed) VALUES ('s1', 'http://localhost', 'My Server', 1, 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, 5, true,
            RiffleDatabase.MIGRATION_1_2,
            RiffleDatabase.MIGRATION_2_3,
            RiffleDatabase.MIGRATION_3_4,
            RiffleDatabase.MIGRATION_4_5,
        )

        db.query("SELECT url, displayName FROM servers WHERE id = 's1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("http://localhost", cursor.getString(0))
            assertEquals("My Server", cursor.getString(1))
        }
    }
}
```

- [ ] **Commit**

```bash
git add core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt
git commit -m "test(database): add MigrationTestHelper tests for all migrations"
```

---

## Task 4: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Append migration instructions to `CLAUDE.md`**

Add the following to the end of `CLAUDE.md`:

```markdown

## Database migrations

When adding a new Room migration:

1. Bump `version` in the `@Database` annotation in `RiffleDatabase.kt` and write the new `MIGRATION_N_(N+1)` companion object.
2. Build the project so KSP exports the new schema JSON to `core/database/schemas/com.riffle.core.database.RiffleDatabase/<N+1>.json`.
3. Register the new migration in `DataModule.kt` inside `addMigrations(...)`.
4. Open `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt` and add:
   - A new `@Test fun migrationNToN1()` following the pattern of existing tests:
     - `helper.createDatabase(TEST_DB, N)` and insert rows exercising every column touched by the migration
     - `helper.runMigrationsAndValidate(TEST_DB, N+1, true, RiffleDatabase.MIGRATION_N_(N+1))`
     - Cursor assertions verifying new columns have correct default values and all pre-existing data is preserved
   - Add the new migration to the `migrateFullChain` test's `runMigrationsAndValidate` call.
```

- [ ] **Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add migration test convention to CLAUDE.md"
```

---

## Running the tests

Migration tests are **instrumented** (they use Android's SQLite layer) and must run on a device or emulator:

```bash
./gradlew :core:database:connectedAndroidTest
```

To run only the migration test class:

```bash
./gradlew :core:database:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.riffle.core.database.MigrationTest
```

Expected output: 5 tests pass (`migration1To2`, `migration2To3`, `migration3To4`, `migration4To5`, `migrateFullChain`).

**If a test fails with a schema hash mismatch:** The placeholder `identityHash` in `1.json` caused a `room_master_table` conflict. Update the hash in `1.json` to the value Room reports in the error message and re-run.

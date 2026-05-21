# Migration Tests Design

## Overview

Add `MigrationTestHelper`-based instrumented tests for all four `RiffleDatabase` migrations (1→2, 2→3, 3→4, 4→5), asserting both schema correctness and data survival after each migration. Backfill the missing `1.json` schema export so `MIGRATION_1_2` can be tested. Add a standing instruction in `CLAUDE.md` requiring new tests whenever a migration is added.

## Scope

- `core/database` module only
- No changes to production code; test infrastructure only
- Covers all four existing migrations plus a full-chain regression test (1→5)

---

## 1. Schema Backfill (`1.json`)

Version 1 contained only the `servers` table. This is confirmed by `MIGRATION_1_2`, which creates `libraries` and `library_items` — tables that therefore didn't exist in v1.

**File:** `core/database/schemas/com.riffle.core.database.RiffleDatabase/1.json`

Content mirrors `2.json` format but with only the `servers` entity and no `libraries`/`library_items` entities. The `identityHash` field must be a valid MD5 string; Room's `MigrationTestHelper.runMigrationsAndValidate` only validates the **final** (target) schema hash, so the source hash is used solely to initialise `room_master_table` and does not affect test correctness.

The `servers` `createSql` is taken verbatim from `2.json` (the table was not altered by any migration, so v1 and v2 share the same DDL).

---

## 2. Dependencies

### `libs.versions.toml`

Add two new library entries (versions already pinned via existing catalogs):

```toml
androidx-room-testing  = { group = "androidx.room", name = "room-testing", version.ref = "room" }
androidx-test-runner   = { group = "androidx.test", name = "runner", version = "1.6.2" }
androidx-test-ext-junit = { group = "androidx.test.ext", name = "junit", version = "1.2.1" }
```

### `core/database/build.gradle.kts`

Add an `androidTest` source-set block:

```kotlin
androidTestImplementation(libs.junit)
androidTestImplementation(libs.androidx.room.testing)
androidTestImplementation(libs.androidx.test.runner)
androidTestImplementation(libs.androidx.test.ext.junit)
```

---

## 3. Test File

**Location:** `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt`

### Setup

```kotlin
private const val TEST_DB = "migration-test"

@RunWith(AndroidJUnit4::class)  // requires androidx.test.ext:junit
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RiffleDatabase::class.java,
    )
}
```

`MigrationTestHelper` discovers schema JSONs from the exported schema directory via the `@Database` annotation on `RiffleDatabase`.

### Test inventory

| Test | Start version | End version | Migration under test |
|---|---|---|---|
| `migration1To2` | 1 | 2 | `MIGRATION_1_2` |
| `migration2To3` | 2 | 3 | `MIGRATION_2_3` |
| `migration3To4` | 3 | 4 | `MIGRATION_3_4` |
| `migration4To5` | 4 | 5 | `MIGRATION_4_5` |
| `migrateFullChain` | 1 | 5 | all four |

### Per-test pattern

Each test follows the same structure:

1. `helper.createDatabase(TEST_DB, fromVersion)` — creates a real SQLite DB at the source schema
2. Insert representative rows via raw `execSQL` (no DAOs; DAOs depend on the current schema)
3. `helper.runMigrationsAndValidate(TEST_DB, toVersion, true, MIGRATION_X_Y)` — runs migration, validates final schema against the target version's exported JSON
4. Query the DB with a raw cursor and assert column values

### Per-migration assertions

**`migration1To2`**
- Insert one `servers` row before migration
- After: `libraries` table exists (query returns empty, no exception); `library_items` table exists; original `servers` row is present

**`migration2To3`**
- Insert one `libraries` row and one `library_items` row (neither has `isUnsupported`/`isSupported` yet)
- After: `libraries` row has `isUnsupported = 0` (default); `library_items` row has `isSupported = 1` (default); all original column values preserved

**`migration3To4`**
- Insert one `libraries` row (to have existing data)
- After: `series`, `collections`, `series_items`, `collection_items` tables all exist and are empty; `libraries` row is untouched

**`migration4To5`**
- Insert one `library_items` row (without `ebookFileIno`)
- After: `library_items` row has `ebookFileIno = NULL`; `reading_positions` table exists and is empty; all other column values on the item are preserved

**`migrateFullChain`**
- Insert one `servers` row at v1
- Migrate to v5 in one `runMigrationsAndValidate` call with all four migrations
- Assert the `servers` row is still present after all migrations

---

## 4. CLAUDE.md Instruction

Append a section to the project `CLAUDE.md`:

```
## Database migrations

When adding a new Room migration:
1. Bump `version` in `@Database` and write the `MIGRATION_N_(N+1)` object in `RiffleDatabase`.
2. Build the project so KSP exports the new schema JSON to `core/database/schemas/`.
3. Open `core/database/src/androidTest/kotlin/com/riffle/core/database/MigrationTest.kt`.
4. Add a new `@Test fun migrationNToN1()` that:
   - Creates a DB at version N
   - Inserts rows that exercise every column touched by the migration
   - Runs and validates the migration
   - Asserts new columns have the correct default values and pre-existing data is preserved
5. Update `migrateFullChain` to include the new migration in the chain.
```

---

## Constraints & Non-goals

- Tests are **instrumented** (run on device/emulator via `androidTest`), not JVM unit tests — `MigrationTestHelper` requires Android's SQLite layer
- No DAO or Hilt usage in tests — raw cursor access only, to avoid coupling tests to the current entity model
- No changes to the production migration objects or database configuration

# Riffle — Agent Instructions

## Running harness tests

Always run harness tests via `make harness-test`. Never call `./gradlew :app:connectedDebugAndroidTest` directly — it targets all connected devices and will interfere with the developer's physical device. The Makefile target boots the "Harness Medium Phone" AVD, runs tests against it exclusively, then shuts it down.

## Feature progress

When an issue is closed (a feature is implemented and merged), mark it as complete in the Features list in `README.md` by changing `- [ ]` to `- [x]` on the corresponding line, and include that change in the PR for that issue.

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

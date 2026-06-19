# Riffle — Agent Instructions

## Installing APKs on emulators

Do not `adb install` a build onto an emulator unless the user explicitly asks, or unless you are about to do the testing yourself (e.g. verifying UI behaviour as part of a task). Building and committing are fine without installing.

## Running harness tests

Always run harness tests via `make harness-test` (phone-form-factor tests) or `make harness-test-tablet` (tests annotated with `@TabletLayout`). Never call `./gradlew :app:connectedDebugAndroidTest` directly — it targets all connected devices and will interfere with the developer's physical device. Each target boots its dedicated AVD ("Harness Medium Phone" or "Harness Medium Tablet"), runs its filtered test subset against it exclusively, then shuts it down. The two subsets are mutually exclusive, so tests never double-run across targets.

## Reader mode changes

Any change that affects reading behaviour — typography, scrolling, navigation, decorations, layout, text size, margins, or similar — must be considered and tested in **both paged and continuous modes**. Continuous mode has distinct scroll mechanics (native Android scroll vs. Readium column pagination) and separate JS injection paths; a fix that works in paged mode often breaks or is a no-op in continuous mode and vice versa.

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

## Reader mode changes

The reader has three modes: paginated, vertical, and continuous.

Paginated and vertical both use Readium's EpubNavigatorFragment (scroll=false vs
scroll=true). Readium drives navigation, emits position updates, and populates Locator
fields automatically.

Continuous uses a custom ContinuousReaderView with a fully manual position pipeline.
Anything Readium provides for free to paginated/vertical must be explicitly computed
and threaded through the continuous onPositionChanged lambda in EpubReaderScreen.kt.

Any change that touches the reader — position tracking, navigation events, new ViewModel
state, UI driven by the current locator — must be verified to work in all three modes,
with particular attention to continuous: if paginated/vertical get something from Readium,
ask whether continuous needs to compute an equivalent.

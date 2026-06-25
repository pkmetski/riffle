# Riffle — Agent Instructions

## Agent skills

### Issue tracker

Issues live in GitHub Issues (`github.com/pkmetski/riffle`). See `docs/agents/issue-tracker.md`.

### Triage labels

Default label vocabulary — `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repo — one `CONTEXT.md` + `docs/adr/` at the root. See `docs/agents/domain.md`.

## Agent rules

### Tests are required before opening a PR

Do not open a PR without tests that cover the fix or new functionality. Every bug fix needs a regression test that fails before the change and passes after; every new feature needs unit and/or integration coverage for its behavior. "Manually verified" is not a substitute for an automated test.

### Validate before claiming done

Every fix or new feature must be validated as actually working before it is marked complete or sent for review. Acceptable validation is one of:

- **Integration / instrumentation tests** that exercise the real code path and pass.
- **Visual verification in an AVD** — install the build on an emulator and reproduce the user-visible behavior end-to-end.

When validating in an AVD, follow the same pattern as the Makefile's `harness-test` target: use the dedicated Harness AVD, run the filtered test/scenario against it exclusively, and shut it down when done. Do not target arbitrary connected devices, and do not interfere with other emulators the developer may have running.

JVM unit tests alone are not sufficient validation for anything that touches Readium, the WebView, the reader UI, or other device-layer code.

### Do not blindly update existing tests to make them pass

When a fix causes existing tests to fail, the tests are usually protecting a prior invariant or regression scenario — updating them mechanically risks silently re-opening the bug they were added to catch.

Before changing any existing test:

1. Trace each failing test back to the commit/PR that introduced it. The commit message + the test's own comments name the invariant.
2. Confirm the new code still upholds that invariant. If it does, the test inputs/assertions should still pass — re-examine the fix, not the test.
3. Only adjust a test when the invariant itself has *deliberately* changed, and call that out explicitly in the PR description.

A fix that requires "updating" several pre-existing regression tests to pass is a warning sign — prefer landing the change at a different layer that leaves the prior guarantees untouched.

## Installing APKs on emulators

Do not `adb install` a build onto an emulator unless the user explicitly asks, or unless you are about to do the testing yourself (e.g. verifying UI behaviour as part of a task). Building and committing are fine without installing.

## Running harness tests

Always run harness tests via `make harness-test` (phone-form-factor tests) or `make harness-test-tablet` (tests annotated with `@TabletLayout`). Never call `./gradlew :app:connectedDebugAndroidTest` directly — it targets all connected devices and will interfere with the developer's physical device. Each target boots its dedicated AVD ("Harness Medium Phone" or "Harness Medium Tablet"), runs its filtered test subset against it exclusively, then shuts it down. The two subsets are mutually exclusive, so tests never double-run across targets.

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

Paginated and vertical both use Readium's `EpubNavigatorFragment` (scroll=false vs scroll=true). Readium drives navigation, emits position updates, and populates Locator fields automatically.

Continuous uses a custom `ContinuousReaderView` with a fully manual position pipeline. Anything Readium provides for free to paginated/vertical must be explicitly computed and threaded through the continuous `onPositionChanged` lambda in `EpubReaderScreen.kt`.

Any change that affects reading behaviour — typography, scrolling, navigation, decorations, layout, text size, margins, position tracking, navigation events, new ViewModel state, UI driven by the current locator — must be considered and tested in **all three modes**, with particular attention to continuous: if paginated/vertical get something from Readium, ask whether continuous needs to compute an equivalent. Continuous has distinct scroll mechanics (native Android scroll vs. Readium column pagination) and separate JS injection paths; a fix that works in paged mode often breaks or is a no-op in continuous mode and vice versa.

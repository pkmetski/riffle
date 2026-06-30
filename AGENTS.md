# Riffle — Agent Instructions

## Building and installing APKs

Do not `assembleDebug` (or any APK build) unless the user explicitly asks. Do not `adb install` a build onto a device or emulator unless the user explicitly asks. Editing code and running JVM tests are fine without building or installing.

## Running harness tests

Always run harness tests via `make harness-test` (phone-form-factor tests) or `make harness-test-tablet` (tests annotated with `@TabletLayout`). Never call `./gradlew :app:connectedDebugAndroidTest` directly — it targets all connected devices and will interfere with the developer's physical device. Each target boots its dedicated AVD ("Harness Medium Phone" or "Harness Medium Tablet"), runs its filtered test subset against it exclusively, then shuts it down. The two subsets are mutually exclusive, so tests never double-run across targets.

## Debugging on the developer's device

When debugging with `[DEBUG-<tag>]` logs the user is reproducing for you, **fetch the logcat yourself** — don't ask the user to paste it. The user's device is connected via `adb`; run e.g. `adb logcat -d | grep DEBUG-<tag>` (use `-d` to dump and exit, not stream). Clear the buffer with `adb logcat -c` before they reproduce so the output isn't drowned in history. If multiple devices/emulators are connected, pick the right one with `-s <serial>` (`adb devices`). Trust the user when they say "reproduced" — go fetch.

## Reader mode changes

The reader has three modes: **paginated**, **vertical**, and **continuous**.

Paginated and vertical both use Readium's `EpubNavigatorFragment` (scroll=false vs scroll=true). Readium drives navigation, emits position updates, and populates Locator fields automatically.

Continuous uses a custom `ContinuousReaderView` with a fully manual position pipeline. Anything Readium provides for free to paginated/vertical must be explicitly computed and threaded through the continuous `onPositionChanged` lambda in `EpubReaderScreen.kt`.

Any change that affects reading behaviour — typography, scrolling, navigation, decorations, layout, text size, margins, position tracking, navigation events, new ViewModel state, or UI driven by the current locator — must be verified to work in all three modes. Continuous has distinct scroll mechanics (native Android scroll vs. Readium column pagination) and separate JS injection paths; a fix that works in paged mode often breaks or is a no-op in continuous mode and vice versa. If paginated/vertical get something from Readium, ask whether continuous needs to compute an equivalent.

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

## Reference the source issue in the PR

When the work originated from a GitHub Issue (e.g. the user asked you to "do #123"), the PR body must include a `Closes #N` line so the merge auto-closes the issue. One line per issue if the PR spans several. Put it near the top of the body, above the change summary.

## Tests are required before opening a PR

Do not open a PR without tests that cover the fix or new functionality. Every bug fix needs a regression test that fails before the change and passes after; every new feature needs unit and/or integration coverage for its behaviour. "Manually verified" is not a substitute for an automated test.

## Validate before claiming done

Every fix or new feature must be validated as actually working before it is marked complete or sent for review. Acceptable validation is one of:

- **JVM tests** that exercise the real code path and pass — sufficient for logic that doesn't touch Readium, the WebView, or the reader UI.
- **Instrumentation tests** via `make harness-test` or `make harness-test-tablet`.
- **Visual verification in the app on an AVD** — only when the user has explicitly asked for a build and install.

JVM unit tests alone are not sufficient validation for anything that touches Readium, the WebView, or device-layer code.

## Always reference constants, never the literal

When a named constant exists for a value (e.g. `AnnotationEntity.TYPE_BOOKMARK = "BOOKMARK"`, `AnnotationEntity.TYPE_HIGHLIGHT`, status codes, mime types, well-known string IDs), use the constant at every call site — including inside string comparisons, when constructing fakes, and in tests. Do not redeclare a local `private const val MIRROR = "BOOKMARK"`, do not paste the literal `"BOOKMARK"` into a comparison, and do not assume the storage value is lowercase / uppercase / camelCase without checking. A typo'd literal silently fails to match the real value but reads as correct in code review — exactly how the `annotation.type == "bookmark"` bug shipped against the database's `"BOOKMARK"`. The same rule applies to tests: a fixture using a literal mirrors a production typo and lets the bug appear green.

## Agent skills

### Issue tracker

Issues live in GitHub Issues (`github.com/pkmetski/riffle`). See `docs/agents/issue-tracker.md`.

### Triage labels

Default label vocabulary — `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repo — one `CONTEXT.md` + `docs/adr/` at the root. See `docs/agents/domain.md`.

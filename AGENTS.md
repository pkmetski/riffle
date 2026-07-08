# Riffle — Agent Instructions

## Building and installing APKs

Do not `assembleDebug` (or any APK build) unless the user explicitly asks. Do not `adb install` a build onto a device or emulator unless the user explicitly asks. Editing code and running JVM tests are fine without building or installing.

## Running harness tests

Always run harness tests via `make harness-test` (phone-form-factor tests) or `make harness-test-tablet` (tests annotated with `@TabletLayout`). Never call `./gradlew :app:connectedDebugAndroidTest` directly — it targets all connected devices and will interfere with the developer's physical device. Each target boots its dedicated AVD ("Harness Medium Phone" or "Harness Medium Tablet"), runs its filtered test subset against it exclusively, then shuts it down. The two subsets are mutually exclusive, so tests never double-run across targets.

## Debugging on the developer's device

When debugging with `[DEBUG-<tag>]` logs the user is reproducing for you, **fetch the logcat yourself** — don't ask the user to paste it. The user's device is connected via `adb`; run e.g. `adb logcat -d | grep DEBUG-<tag>` (use `-d` to dump and exit, not stream). Clear the buffer with `adb logcat -c` before they reproduce so the output isn't drowned in history. If multiple devices/emulators are connected, pick the right one with `-s <serial>` (`adb devices`). Trust the user when they say "reproduced" — go fetch.

Assume the user is testing on the correct/latest version of the app. Don't diagnose a reported bug as "wrong APK installed" based on the emulator's build SHA, the drawer version string, or a missing symbol in the installed commit — the user is typically reproducing on their physical device (or a fresh install), not on the emulator you're driving. Treat the bug as real and dig into the code.

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

**Do not skip this step.** The following rationalisations are all wrong and produce PRs that will be sent back:

- "The change is a one-line param flip." → The one-liner is exactly what regresses. Pin it with a test that would flip if someone reverted it.
- "The behaviour lives inside a Composable, so it's not JVM-testable." → Extract the decision into an `internal` top-level function or a helper and unit-test that. A tiny refactor is cheaper than a re-review.
- "The existing tests already cover the surrounding logic." → They cover the surrounding logic, not the specific decision the fix changes. If the fix flipped `X` to `Y`, there must be a test that asserts the value is now `Y`.
- "Instrumentation would be the right level but it's heavy." → Then extract and unit-test the pure decision at the JVM level. Do not open the PR without any test.
- "I'll rely on the user's manual verification." → No. Automated coverage is required in addition to manual verification.
- "Updating docstrings / renaming a stale test counts as test coverage." → It doesn't. Docstring changes cannot fail. A regression test is an assertion that would flip red if the fix were reverted.

Before opening the PR, name the specific assertion(s) that would fail if the fix were reverted line-for-line. If you can't name one, you haven't written the regression test yet.

## Don't blindly update tests

Tests exist to lock in fixes — a passing regression test is the guarantee that a previously-fixed bug hasn't been reintroduced. Every test assertion is a claim someone made about behaviour that must hold. Changing that claim without understanding it is how regressions come back.

This applies in two situations:

**When a test fails after your change.** The default assumption is that your change is wrong, not that the test is stale. "The test is failing so I updated it to match the new output" is the exact motion that reintroduces regressions.

**When updating tests as part of a refactor.** A refactor is supposed to preserve behaviour, so tests should keep passing untouched. If a refactor forces test edits, that's a signal — either the refactor changed behaviour (not a pure refactor), or the test was coupled to internals rather than behaviour. Either way, stop and understand before editing.

Before editing any assertion, fixture, or expected value in an existing test, you must be able to answer:

- What bug or behaviour was this test originally pinning? (Check `git blame` / `git log -p` on the test file — look for the commit that introduced the assertion.)
- Is that behaviour still required? If yes, the test stays as-is and your code must satisfy it. If no, say so explicitly in the PR body and name the assertion you changed and why.
- If I flip the assertion, what stops the original bug from silently returning?

Mechanical updates (renaming a symbol the test references, adjusting a constructor signature) are fine. Semantic updates (changing an expected value, removing an assertion, loosening a matcher) require the justification above.

Deleting or `@Ignore`-ing a red test to unblock a PR is never acceptable.

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

### Logger channels

Production log tags are typed in `core/logging/src/main/kotlin/com/riffle/core/logging/LogChannel.kt` (`RIFFLE_RA`, `RIFFLE_AB`, `RIFFLE_HANDOFF`). Add a new channel by adding an enum entry; never introduce a new `Log.d("RIFFLE_*", …)` literal directly. Inject `Logger` (production: `AndroidLogger`; tests: `RecordingLogger`) and call `logger.d(LogChannel.X) { "msg" }`. The `checkRiffleLogTags` gradle task (wired into `check`) fails CI if a literal leaks back in.

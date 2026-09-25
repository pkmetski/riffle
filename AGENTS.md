# Riffle — Agent Instructions

## Locating UI elements — use testTag, not coordinates

**Never tap or interact with a UI element by screen coordinate.** Every interactive control in Riffle is tagged with a stable `testTag` that surfaces as `resource-id` in `adb shell uiautomator dump` (Android) and as `accessibilityIdentifier` in the XCUITest tree (iOS). Use these names to find elements before interacting.

### Finding an element

On Android (uiautomator):

```bash
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml /tmp/ui.xml
grep 'resource-id' /tmp/ui.xml | grep riffle   # find by resource-id
```

On Android (Compose semantics — preferred in harness tests):

```kotlin
composeRule.onNodeWithTag(TestTags.READER_BACK).performClick()
```

On iOS (XCUITest):

```swift
app.buttons[TestTags.IOS_READER_BACK].tap()
// or by accessibilityIdentifier
app.buttons.matching(identifier: "ios_reader_back").firstMatch.tap()
```

### Tag catalogue

All tag constants live in `feature/design-system/src/commonMain/kotlin/com/riffle/feature/designsystem/TestTags.kt`. Before searching for an element by coordinate or label text, **look up its constant** there. The file is organised by screen area — reader, library, player, settings, downloads, etc.

### Adding tags to new or untagged controls

**Every interactive control must carry a `testTag`.** This is not optional.

- **New controls**: add `.testTag(TestTags.YOUR_CONSTANT)` at the time you write the control. Add the constant to `TestTags.kt` first, following the naming convention of the surrounding section (`SCREEN_CONTROL`, e.g. `READER_BACK`, `LIBRARY_FILTER`).
- **Existing controls you encounter without a tag**: add the tag in the same PR, even if the control is not the primary subject of your change. An untagged control you touch is an untagged control you own.
- **Dynamic/per-item controls**: use the `fun` helpers already defined in `TestTags` (e.g. `TestTags.playlistRow(id)`, `TestTags.cbzThumb(pageIndex)`). Never interpolate the string directly at the call site.
- **Never use a raw string literal** as a `testTag` argument — always reference a `TestTags` constant. The `checkRiffleLogTags` analogy: a typo'd literal silently never matches. The constant is the single source of truth.
- **iOS**: Compose `Modifier.testTag(…)` maps to `accessibilityIdentifier` automatically in the CMP bridge. No extra iOS-side work is needed for shared Compose screens. iOS-only UIKit or SwiftUI surfaces must set `.accessibilityIdentifier(TestTags.IOS_*)` manually.

## GitHub issue/PR operations

Use the `gh` CLI for all GitHub write operations (creating issues, PRs, comments). The GitHub MCP server (`mcp__github__issue_write`, `mcp__github__create_pull_request`, etc.) does not have write access to this repo and will return 403 — do not retry with it.

## Building and installing APKs

Do not `assembleDebug` (or any APK build) unless the user explicitly asks. Do not `adb install` a build onto a device or emulator unless the user explicitly asks. Editing code and running JVM tests are fine without building or installing.

## Running harness tests

Always run harness tests via `make harness-test` (phone-form-factor tests) or `make harness-test-tablet` (tests annotated with `@TabletLayout`). Never call `./gradlew :app:connectedDebugAndroidTest` directly — it targets all connected devices and will interfere with the developer's physical device. Each target boots its dedicated AVD ("Harness Medium Phone" or "Harness Medium Tablet"), runs its filtered test subset against it exclusively, then shuts it down. The two subsets are mutually exclusive, so tests never double-run across targets.

## Debugging on the developer's device

When debugging with `[DEBUG-<tag>]` logs the user is reproducing for you, **fetch the logcat yourself** — don't ask the user to paste it. The user's device is connected via `adb`; run e.g. `adb logcat -d | grep DEBUG-<tag>` (use `-d` to dump and exit, not stream). If multiple devices/emulators are connected, pick the right one with `-s <serial>` (`adb devices`). Trust the user when they say "reproduced" — go fetch.

Assume the user is testing on the correct/latest version of the app. Don't diagnose a reported bug as "wrong APK installed" based on the emulator's build SHA, the drawer version string, or a missing symbol in the installed commit — the user is typically reproducing on their physical device (or a fresh install), not on the emulator you're driving. Treat the bug as real and dig into the code.

**Never, under any circumstances, suggest "old APK" or "stale cache" as the explanation for a reported bug.** The user builds and installs their own APK and knows what version they're running. If a JVM test passes but the user reports the bug is still present, that means the test is wrong or insufficient — not that the user is running old code. Investigate the code; never push the problem back onto the user's installation.

## Developer test services

Use these local services when an investigation needs a live source backend:

| Source | Endpoint | Username | Password |
|--------|----------|----------|----------|
| Audiobookshelf | `http://media-server:13378` | `test2` | `test` |
| Komga | `http://media-server:25600` | `test@test.test` | `test` |
| Kavita | `http://media-server:5000` | `test` | `test12` |

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
4. Open `core/database/src/androidDeviceTest/kotlin/com/riffle/core/database/MigrationTest.kt` and add:
   - A new `@Test fun migrationNToN1()` following the pattern of existing tests:
     - `helper.createDatabase(TEST_DB, N)` and insert rows exercising every column touched by the migration
     - `helper.runMigrationsAndValidate(TEST_DB, N+1, true, RiffleDatabase.MIGRATION_N_(N+1))`
     - Cursor assertions verifying new columns have correct default values and all pre-existing data is preserved
   - Add the new migration to the `migrateFullChain` test's `runMigrationsAndValidate` call.
5. **If the migrated table is used on iOS** (check whether `IosRiffleDatabaseSchema.kt` creates the table and whether any `Ios*Dao.kt` touches the changed column):
   - Bump `version` in `IosRiffleDatabaseSchema.kt`.
   - Add the corresponding DDL to the `migrate()` call for the new version range.
   - Open `core/database/src/iosTest/kotlin/com/riffle/core/database/IosRiffleDatabaseSchemaTest.kt` and add a `@Test fun migrateVNToVN1()` following the pattern of the existing migration tests:
     - Drop or alter the table to put the database into a genuine pre-migration shape.
     - Call `IosRiffleDatabaseSchema.migrate(driver, (N-1).toLong(), N.toLong())`.
     - Assert that the new columns or tables exist and pre-existing data is preserved.
   - Add the new version range to the `migrateFullChain` test's `migrate(driver, 1L, N.toLong())` call in `IosRiffleDatabaseSchemaTest.kt`.

## Commit every uncommitted change on the branch

When finalizing, `git status` is almost never empty — the user routinely piggy-backs work in progress onto whatever branch is in flight. Every modified or untracked file that isn't your own scratch/debug artifact belongs on the branch and must be committed before the rebase / PR. **Unrelated ≠ unwanted.** Never stash-and-pop across the rebase (it silently drops the WIP from the PR), and never stop to ask "is this related?" — the answer is that the user knew the state of their tree when they invoked `/finalize`.

- If a change is clearly part of the same fix, fold it into the pending commit.
- If it's a whole separate feature or unrelated fix, give it its own commit with a descriptive message and mention it in the PR body so it's not a surprise in the diff.
- Only stop and ask when a file looks genuinely suspicious (potential secret, large binary that doesn't belong).

## Reference the source issue in the PR

When the work originated from a GitHub Issue (e.g. the user asked you to "do #123"), the PR body must include a `Closes #N` line so the merge auto-closes the issue. One line per issue if the PR spans several. Put it near the top of the body, above the change summary.

## No real book titles in commits or PRs

Never include real book titles in commit messages, PR titles, or PR descriptions. If you need to refer to a specific book, use the book's library ID instead. This avoids leaking the user's reading list in public repository history.

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

**No pre-existing failures.** There is no such thing as a pre-existing failure that is acceptable to leave in. If `./gradlew test jvmTest` (or any test task) reports a failure on the branch — regardless of whether the same failure exists on main — it must be fixed before opening a PR. "It was already failing on main" is not an excuse; main is the quality gate, and anything that fails locally fails the bar.

**Enforced by `checkTestGuardrails`** (part of `check` and run on CI via `riffleChecks` in the Lint job): any `@Test` function that exists at the merge base with main but not on the branch — deleted or renamed — fails the build unless a commit message on the branch carries a `Removed-test: <exact test name>` trailer (one line per test; backticks optional). The trailer is a declaration, not a bypass: the commit body and PR must still explain which behavioral claim is being retired and why, per the questions above. Moving a test between files needs no trailer. Detection logic lives in `buildSrc/src/main/kotlin/com/riffle/buildlogic/TestGuardrailLint.kt`.

## No empty commits to retrigger CI

Never push a `git commit --allow-empty` (or any commit whose sole purpose is to kick off a new CI run). If CI fails with an infrastructure flake (emulator boot failure, network blip, ZIP corruption), wait — the failure is transient and the next legitimate push will retrigger it. If all outstanding CI jobs are infrastructure failures and there is genuinely no code fix to make, say so to the user and ask them to retrigger manually. An empty commit pollutes the branch history and makes `git log` misleading.

## Validate before claiming done

Every fix or new feature must be validated as actually working before it is marked complete or sent for review. Acceptable validation is one of:

- **JVM tests** that exercise the real code path and pass — sufficient for logic that doesn't touch Readium, the WebView, or the reader UI.
- **Instrumentation tests** via `make harness-test` or `make harness-test-tablet`.
- **Visual verification in the app on an AVD** — only when the user has explicitly asked for a build and install.

JVM unit tests alone are not sufficient validation for anything that touches Readium, the WebView, or device-layer code.

## After two failed fix attempts, verify on AVD before claiming a fix

If a reported bug has survived two of your fix attempts (i.e. the user reproduced it a second time after you claimed it fixed), you must build the APK, install it on the AVD, drive the exact repro path yourself, and confirm the bug is gone before telling the user it's fixed. Do not push a third fix and ask them to test; the verification is on you at that point.

This overrides the general "do not build or install without permission" and "do not touch other emulators" rules for that specific fix cycle — the two failed attempts are the standing authorization. Use your own dedicated AVD by serial (per the ephemeral-AVD/never-touch-other-emulators guidance elsewhere in this file), install the debug APK, and check the repro path with logcat and (for UI bugs) a screenshot. If the third attempt still doesn't work on device, say so explicitly; do not claim it's fixed based on a compile-and-tests-pass signal alone.

## Always reference constants, never the literal

When a named constant exists for a value (e.g. `AnnotationEntity.TYPE_BOOKMARK = "BOOKMARK"`, `AnnotationEntity.TYPE_HIGHLIGHT`, status codes, mime types, well-known string IDs), use the constant at every call site — including inside string comparisons, when constructing fakes, and in tests. Do not redeclare a local `private const val MIRROR = "BOOKMARK"`, do not paste the literal `"BOOKMARK"` into a comparison, and do not assume the storage value is lowercase / uppercase / camelCase without checking. A typo'd literal silently fails to match the real value but reads as correct in code review — exactly how the `annotation.type == "bookmark"` bug shipped against the database's `"BOOKMARK"`. The same rule applies to tests: a fixture using a literal mirrors a production typo and lets the bug appear green.

## iOS/Android multi-platform parity

**Every bug fix, feature implementation, or modification — including tests, refactors, UI tweaks, and behaviour adjustments — must be applied to both Android and iOS, in the same PR.** There are no exceptions based on perceived scope or platform origin. A fix introduced in `androidMain` still requires the equivalent in `iosMain` (or `commonMain` if the logic can be shared). A test added for Android still requires a matching iOS scenario. This rule is not optional and applies equally to fixes in `androidMain`, `commonMain`, and `iosMain`.

"Applied to iOS" means the behaviour **works on iOS**, not that it compiles or that an interface is bound. Binding an `IosNoOp*` stub, leaving a `TODO`, or routing a preference to a store that nothing reads does not satisfy the rule. The iOS side must be exercised by a test that runs the iOS code path (see "Tests must mirror both platforms") and, for anything user-visible, checked on the simulator.

The following rationalisations are wrong and will result in the PR being sent back:

- "It's just a one-liner on Android." → One-liners regress on iOS too. Implement it there.
- "The bug only manifests on Android." → Verify it doesn't exist on iOS; document findings either way.
- "I'll do the iOS side in a follow-up." → No. Both platforms ship together or neither ships.
- "The logic is in `commonMain` so iOS is covered." → Only if iOS actually exercises that code path. Verify it and add a test.
- "iOS has an implementation of that interface." → A no-op or stub is not an implementation. The 2026-09-16 parity pass found ~30 such bindings behind features that looked complete on paper (#1044–#1049).
- "This is a modification to an existing Android screen, not a new feature." → Modifications count. If the iOS screen lacks the surface being modified, that gap is part of the change.
- "iOS doesn't support X." → Only OS-level constraints qualify, and only with an ADR saying so. A PR body note is not an exemption.

### Code reuse comes first

Before writing any platform-specific implementation, ask: can this logic live in `commonMain`? The answer is usually yes for ViewModels, domain logic, business rules, and network calls. Platform-specific code (`androidMain` / `iosMain`) is only acceptable for things that are genuinely impossible to share: Android Views/Fragments, iOS UIKit/AVFoundation bindings, and expect/actual seams for APIs with no KMP equivalent.

- **Wrong**: duplicate the logic in `androidMain` and `iosMain`.
- **Right**: extract to `commonMain`, bind platform-specific backends behind an `interface` or `expect/actual`.

When an Android implementation is being moved or a new feature is being added, check whether any existing `androidMain` code can be lifted to `commonMain` at the same time. Leave the codebase more shared after every PR, never less.

### Where shared UI goes — the module topology matters

Most `feature:*` modules are `jvm() + iosArm64 + iosSimulatorArm64`. **A jvm-target Compose artifact cannot be consumed by an Android application**, so Compose UI placed in one of those modules can never be rendered by `:app` — it will silently become iOS-only and its Android twin will be written separately. That is the mechanism behind most of the UI duplication in this repo.

Shared Compose belongs in a module with an **`android { }` + iOS** topology. `feature:source-ui` is the reference: it has `androidTarget` + both iOS targets, material3, `coil.compose` and `composeResources`, and **both `:app` and `:shared` depend on it and render its screens**. Its own KDoc states the rule — *"Everything Compose that both platforms render belongs here."*

So, before writing a screen or component:

- **Pure logic** (derivations, mappers, view-model state) → an existing `feature:*` `commonMain`. Both hosts call it.
- **Compose that both platforms render** → a module with the `feature:source-ui` topology. Do not put it in a `jvm()+ios` module and do not write it twice.
- **Genuinely host-specific UI** (Readium-Android fragment hosting, UIKit bridges) → `:app` or `:shared` respectively.

`:app` is the Android host and `:shared` is the iOS host. Anything that lands in either is single-platform by construction and becomes a parity gap the moment the other platform needs it.

**Never keep a private platform copy of a derivation that already exists in `commonMain`.** This is the most common way the two platforms silently drift apart, because nothing fails: both copies compile, both suites stay green, and the screens quietly render different things. Real examples found in the 2026-09-19 pass — iOS's `SettingsScreen.kt` had private duplicates of the reader-settings summaries that read `"Sans-serif"`/`"Monospace"` against Android's `"Sans serif"`/`"Mono"`, showed line spacing where Android showed margins, and used `toInt()` instead of `roundToInt()` so a 1.15 font scale rendered 114% on iOS and 115% on Android; `IosEpubReaderScreen.kt` kept its own `flattenToc` that rendered blank-title containers the shared one deliberately skips; and `comicDisplaySummary` existed twice with different casing and two missing segments.

Before adding any `private fun` that maps a `core:domain` enum to a string, formats a summary, or computes a layout, grep for an existing shared implementation and call it. If the shared one is wrong for your platform, fix the shared one or add a parameter — do not fork it.

**A binding is not a call path.** "Implemented on iOS" means something actually invokes it at runtime. A class can be real, registered in Koin, covered by a graph test, and still be dead because no screen ever calls it. When claiming a feature works on iOS, trace the path from the user-facing entry point to the implementation and name it.

### Tests must mirror both platforms

For every Android harness/integration/unit test that covers the changed behaviour, there must be a corresponding iOS test — no exceptions. If an Android test is added without an iOS counterpart, the PR will be sent back.

**The counterpart must execute the iOS code path.** An Android test that pins behaviour in `app`/`androidMain` proves nothing about iOS when iOS has its own implementation of that behaviour — every iOS defect found in the 2026-09-16 parity pass (#1044–#1049) sat in code only iOS executes while the relevant Android tests were green. Therefore:

- **Preferred:** one implementation in `commonMain`, one test in `commonTest`. CI runs every module's `commonTest` on `iosSimulatorArm64Test`, so the same assertion covers both platforms.
- **Platform-bound code** (Swift bridges, iOS DAOs, PDFKit/AVFoundation/Readium-Swift hosting) gets a real XCTest that drives the iOS implementation.
- **Never acceptable as the iOS counterpart:** an `XCTSkip` placeholder, a skip that fires when no server/fixture is present (that is an assertion — use `XCTFail`), a Swift test that re-asserts a Kotlin `commonTest` already running on iOS, or a trivial non-nil/constant-echo check.
- When adding any Android test, the PR body names the iOS test that covers the same behaviour on the iOS path, or adds it.
- **Exception — JVM-primitive threading tests:** An `androidHostTest` or `jvmTest` that uses `Thread.currentThread()` or `java.util.Collections.synchronizedSet` to verify that `flowOn(dispatchers.default)` moves computation off Main has no direct iOS counterpart because those APIs do not exist in Kotlin/Native. This is acceptable **only when both of the following hold:** (1) the logic under test is in `commonMain` — the `flowOn` operator is part of the Kotlin coroutines library and runs identically on iOS, so the threading guarantee transfers automatically; and (2) the functional behaviour (that results are computed correctly) is covered by a `commonTest` that exercises the iOS code path. When these two conditions hold, name both the threading test and the commonTest functional counterpart in the PR body. Follow the pattern of `LibraryFilterEngineThreadingTest` (jvmTest) and `UnboundedBrowseViewModelThreadingTest` (androidHostTest).
- **Exception — ViewModel with Room-coupled concrete constructor params:** When a ViewModel's constructor includes a concrete class whose own constructor chains into a Room DAO (e.g. `WebSourceItemGate → RemoteItemFreshness → RemoteItemFreshnessDao`), constructing that ViewModel in `commonTest` is infeasible because Room is Android-only. In this case, `androidHostTest` covers the VM-level integration (using mockk for the concrete dependency), and `commonTest` in the underlying domain/source module covers the pure-function iOS path. Document both in the PR body.

This means:

1. Identify the relevant Android test classes (harness tests in `app/src/androidTest`, unit tests in `**/test`).
2. Implement each scenario as an XCTest in `iosApp/iosAppTests/`:
   - Non-`XCUIApplication` logic tests → `iosAppUnitTests` target (runs in `Unit Tests` CI job alongside the KMP commonTest suite).
   - `XCUIApplication`-based flows → `iosAppTests` target (runs in `Harness tests (phone)` CI job).

The iOS XCTest suite runs in two CI tiers that mirror Android's: `Unit Tests` (KMP + XCTest unit tests, `iosAppUnitTests` target) and `Harness tests (phone)` (full-app flows, `iosAppTests` target). A PR that adds Android tests without the corresponding iOS coverage will be sent back.

Likewise, if an iOS test is added, the equivalent Android coverage must also be present or already exist.

### A test that isn't wired never runs

Writing the test is not enough — on both platforms a test can exist, compile, and be silently excluded. Check the wiring in the same PR:

- **`commonTest` runs on iOS only if the module's task is listed in `.github/workflows/ios.yml`.** Adding a new module, or a first `commonTest` source set to an existing one, requires adding `:<module>:iosSimulatorArm64Test` to that list. Miss it and the tests pass locally and never execute in CI.
- **A Swift file runs only if it is a member of an Xcode target.** `NavDrawerTests.swift` sat in `iosApp/iosAppTests/` for months as a member of neither target; its three tests had never executed once. When they were finally wired up, two of them failed immediately — they had been written against Android's affordances. After adding a Swift test file, confirm its target membership in `iosApp/iosApp.xcodeproj/project.pbxproj`.
- **Zero `XCTSkip`.** `grep -rn "XCTSkip" iosApp/` must come back empty. A skip that fires when a fixture or server is missing is an assertion in disguise — use `XCTFail`.

### Kotlin/Native and kotlin.test traps when moving a test to `commonTest`

Moving an Android JVM test into `commonTest` is usually mechanical, but three things break quietly:

- **Kotlin/Native rejects both commas and parentheses inside backtick-quoted test names** (`Name contains illegal characters`). JVM allows them. Renaming a test requires a `Removed-test:` trailer, so prefer plain camelCase identifiers for new shared tests.
- **`kotlin.test` assertions are message-LAST; JUnit's are message-FIRST.** `assertTrue(msg, cond)` becomes `assertTrue(cond, msg)` and `assertEquals(msg, expected, actual)` becomes `assertEquals(expected, actual, msg)`. Getting this wrong compiles cleanly and asserts nonsense — typically comparing a message string to a value.
- **JVM-only APIs that have no `commonMain` equivalent**: `String.format`, `java.text.Normalizer`, `java.net.URLEncoder`, `Map.putIfAbsent`, `Map.merge`, `java.util.UUID`, `java.security.MessageDigest`. Each needs a shared replacement or an `expect`/`actual` seam — and the replacement must reproduce the JVM behaviour exactly, because the moved tests pin it.

### Minimum checklist for every PR (features, fixes, tests, refactors)

- [ ] Feature/fix logic lives in `commonMain` (or has a documented reason it cannot).
- [ ] No private platform copy of a shared derivation was added.
- [ ] Android harness tests pass: `make harness-test` / `make harness-test-tablet`.
- [ ] iOS implementation present **and reachable** — the call path from the UI to it is named in the PR body.
- [ ] iOS XCTest scenarios implemented in `iosApp/iosAppTests/` in the correct target (`iosAppUnitTests` for logic, `iosAppTests` for UI flows), and the files are members of that target.
- [ ] New/changed `commonTest` modules are listed in `.github/workflows/ios.yml`.
- [ ] Everything under "Compile every source set CI compiles" below is green.
- [ ] `xcodebuild test` passes on iOS simulator for both targets.
- [ ] If a change cannot be made on iOS (genuine platform constraint), this is documented in the PR body with a justification and a follow-up issue opened.

### Compile every source set CI compiles

**`./gradlew test jvmTest` is not sufficient and must not be the only check before pushing.** It compiles neither `app/src/androidTest` nor `core:data`'s `androidHostTest`, so a change that breaks either passes locally and fails on CI. This bit the 2026-09-19 parity work twice in a row: a new `SourceDao` method left four `androidHostTest` fakes incomplete, and a batch of moved helpers left eight `androidTest` files without imports. Both were invisible to `test jvmTest`.

Run all of these before pushing, sequentially — parallel Gradle invocations corrupt `:app`'s KSP caches in this repo:

```
./gradlew test jvmTest riffleChecks
./gradlew :app:compileDebugAndroidTestKotlin       # app/src/androidTest — NOT covered above
./gradlew :core:data:compileAndroidHostTest        # androidHostTest — NOT covered above
./gradlew <each touched module>:iosSimulatorArm64Test
```

The iOS `Lint` CI job runs **SwiftLint** as well as ktlint, and `swiftlint lint iosApp/` exits non-zero on pre-existing violations — so compare your branch's violation set against `main`'s rather than reading the exit code. Note also that the ktlint gate only covers the `iosMain` source sets listed in `.github/workflows/ios.yml`, so violations in `iosTest` escape CI and still need `:<module>:ktlintCheck` locally.

## Agent skills

### Issue tracker

Issues live in GitHub Issues (`github.com/pkmetski/riffle`). See `docs/agents/issue-tracker.md`.

### Triage labels

Default label vocabulary — `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repo — one `CONTEXT.md` + `docs/adr/` at the root. See `docs/agents/domain.md`.

### Logger channels

Production log tags are typed in `core/logging/src/main/kotlin/com/riffle/core/logging/LogChannel.kt` (`RIFFLE_RA`, `RIFFLE_AB`, `RIFFLE_HANDOFF`). Add a new channel by adding an enum entry; never introduce a new `Log.d("RIFFLE_*", …)` literal directly. Inject `Logger` (production: `AndroidLogger`; tests: `RecordingLogger`) and call `logger.d(LogChannel.X) { "msg" }`. The `checkRiffleLogTags` gradle task (wired into `check`) fails CI if a literal leaks back in.

### Source/Service taxonomy (no new `Server`)

The taxonomy is Source/Service (ADR 0049). The `checkNoServerReferences` gradle task (wired into `check`) fails CI if a Kotlin file outside the grandfathered allowlist introduces a `\bServer[A-Z]` identifier (e.g. `ServerType`, `ServerRepository`) or the bare literal `serverId`. New sites must use `SourceFoo` / `ServiceFoo` and `sourceId`; if a file legitimately belongs to Storyteller-adjacent internals or historical Room migration SQL, add it to `ServerReferenceLint.ALLOWLIST` in `buildSrc/` with a one-line justification. Detection logic lives in `buildSrc/src/main/kotlin/com/riffle/buildlogic/ServerReferenceLint.kt`.

### Platform-agnostic core (no Android imports)

The multi-platform-core modules (`core:common`, `core:models`, `core:domain`, `core:net`, `core:sources`, `core:sync`, `core:annotations`) must keep their `commonMain` production code platform-neutral so a future KMP target can consume it unchanged. The JVM-only `core:network` shim is also scanned to prevent Android API drift while its streaming APIs remain host-specific. The `checkNoAndroidImports` gradle task (wired into `check`) fails CI if shared production code in those modules imports `android.*`, `androidx.*` (except `androidx.annotation`), or `java.util.logging`; platform-specific KMP source sets are excluded. `core:annotations` does not exist yet — the check no-ops for missing directories and activates automatically when the module is created. If a file legitimately needs an Android dependency it belongs in a hosting or platform source set (`core:data`, `core:network`, `core:logging`, `app`, `androidMain`, `jvmMain`, or `iosMain`), not shared core. Only in exceptional cases add its path to `AndroidImportLint.ALLOWLIST` with a one-line justification. Detection logic lives in `buildSrc/src/main/kotlin/com/riffle/buildlogic/AndroidImportLint.kt`. See [ADR 0059](docs/adr/0059-platform-agnostic-core-boundary.md) for the full rationale and module map.

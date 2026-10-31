---
name: finalize
description: Finalize the current branch — rebase on main, address review feedback, then open a PR. Use when the user says "finalize", "wrap this up", or asks to get a branch ready to merge.
---

# Finalize

Run these phases in order. Default to auto-resolving mechanical obstacles — rebase param-list/import/registry unions, stale generated-code that just needs a clean rebuild, missing test arguments after adding a new constructor parameter, etc. Only stop and surface when resolution requires a real judgment call (overlapping edits to the same logic, semantic ambiguity, unclear intent).

## 1. Rebase on main

1. `git status --porcelain` — if non-empty, **triage every file before stashing**. Uncommitted changes are almost always the user's in-progress work that belongs on this branch; stashing then popping after the rebase silently leaves them out of the PR. The user often piggy-backs small unrelated fixes on whatever PR is in flight, so unrelated ≠ unwanted — the default is to include them. For each modified/untracked path:
   - If the change is a scratch/debug artifact YOU created and forgot to clean up (temporary `[DEBUG-…]` logs, throwaway prototype files, un-reverted instrumentation), revert or delete it.
   - Otherwise it's the user's work — commit it. Give it its own commit with a descriptive message when it's unrelated to the main task; fold it into the pending work only when it's clearly part of the same fix. Either way, mention it in the PR body so it's not a surprise in the diff.
   - Only stop and ask if the file looks genuinely suspicious (contains what could be secrets, or is a large binary/generated artifact that doesn't belong in the repo). Do not stop for "is this related?" — commit and note it.
   - Never silently stash-and-pop, because that hides the file from the PR *and* from your final report.
2. Only after `git status --porcelain` is empty: `git fetch origin`.
3. `git rebase origin/main`
   - On conflict: auto-resolve when the resolution is mechanical and unambiguous (both sides added a parameter to the same list, both sides added an import, both sides added an entry to a registry, etc.). Don't stop and ask for trivial unions.
   - Only stop and surface the conflict when resolution requires a judgment call — overlapping edits to the same logic, semantic ambiguity, or unclear intent.
4. **Before proceeding to Phase 2, re-run `git status --porcelain`** — it must be empty. A non-empty status here means Phase 1's triage missed a file (or the rebase left one behind), and Phases 6–8 will push a PR that doesn't include it. Loop back to step 1 if anything shows up.

## 2. Implement any missing tests

Review `git diff origin/main...HEAD` and identify any new behaviour, bug fixes, or logic changes that lack test coverage. For each gap:

- Write the missing test(s) — unit tests for pure logic, integration tests for database/network boundaries, harness tests for UI flows.
- Commit the new tests as a single new commit before moving on.

**Run every newly introduced or modified test locally before proceeding to Phase 3.** Do not rely on CI to catch a broken test you just wrote. From the diff, collect every `@Test` function that was added or changed on this branch — those are the only tests to run. Do not run the full suite; target exactly those classes/functions.

Use the appropriate command for each test type, scoped to only the affected classes:

- **JVM / commonTest** (new/modified `@Test` functions in `**/test/**` or `**/commonTest/**`): `./gradlew test jvmTest --tests "com.riffle.…MyTest"` — one `--tests` filter per changed class; never omit the filter and run the whole suite.
- **Harness tests** (new/modified `@Test` functions in `app/src/androidTest/` or annotated `@TabletLayout`): `make harness-test` / `make harness-test-tablet` — the Makefile targets already filter to their respective subsets.
- **iOS XCTest** (new/modified Swift test functions in `iosApp/iosAppTests/` or `iosApp/iosAppUnitTests/`): `xcodebuild test -workspace iosApp/iosApp.xcworkspace -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:<Target>/<Suite>/<TestName>`.

**If the local run has not finished within 10 minutes**, do not block on it — proceed to Phase 4 and continue through to the push in Phase 8 while the run completes in the background. Monitor its output; if it finishes green before you push, great. If it finishes red before you push, fix the failure first. If it is still running when you would push, push anyway and keep monitoring; treat any subsequent failure as you would a CI failure in Phase 9.

If any newly introduced or modified test fails locally (and the run has finished), fix it — or the production code it exercises — before moving on. Do not push a test that is red locally.

Skip the run only if the diff is purely mechanical (rename, config change, documentation) with no tests introduced or modified.

## 3. iOS/Android parity and code-duplication audit

Review `git diff origin/main...HEAD` for every file that touches `androidMain`, `iosMain`, `app/`, or `iosApp/`. For each change found:

### 3a. Platform counterpart check

- **Android-only change** (file under `androidMain`, `app/src/main`, `app/src/androidTest`): verify an equivalent iOS change exists in `iosMain`, `iosApp/`, or `commonMain`. If not, implement the iOS counterpart now. Rationalising "I'll do iOS later" is not acceptable — both platforms ship together.
- **iOS-only change**: same rule in reverse — verify Android is covered.
- **`commonMain` change**: verify both platforms actually exercise the changed code path. Grep for the changed class/function in both `androidMain`/`app/` and `iosMain`/`iosApp/` to confirm it's reachable from each host. A binding that exists but is never called is not coverage.

### 3b. Code-duplication check

For every new `private fun`, helper, mapper, or formatter added in `androidMain` or `iosMain`:

1. `grep -r "fun <name>" feature/ core/ shared/src/commonMain` — if an equivalent already exists in `commonMain`, use it instead of forking.
2. If the new function is genuinely platform-neutral (no Android/iOS-only imports), move it to `commonMain` and call it from both sides in the same commit.
3. If a private platform copy of a shared derivation already existed before this branch and the diff touches it, consolidate it now rather than propagating the fork.

### 3c. Module topology check

For every new Compose screen or component added on this branch:

- If it will be rendered by **both** platforms → it must live in a module with `androidTarget + iosArm64 + iosSimulatorArm64` topology (e.g. `feature:source-ui`, `feature:library-ui`). A `jvm() + iOS` module cannot be consumed by `:app`; placing shared UI there creates silent Android-only-ness.
- If it is genuinely host-specific (Readium-Android fragment, UIKit bridge) → `:app` or `:shared/iosMain` respectively.
- Verify no `@Composable` was added to `shared/src/commonMain` (run `grep -r "@Composable" shared/src/commonMain` — must return empty).

### 3d. Test-parity check

For every new `@Test` function added in `app/src/androidTest/`, `**/androidHostTest/`, or `**/test/` (Android-only):

1. Name the iOS counterpart that covers the same behaviour on the iOS code path. If it doesn't exist yet, write it now (in `iosApp/iosAppTests/` or `iosApp/iosAppUnitTests/` per AGENTS.md).
2. Confirm the Swift file is a member of the correct Xcode target in `project.pbxproj`.
3. If the change adds a new `commonTest` source set to a module, add `:<module>:iosSimulatorArm64Test` to `.github/workflows/ios.yml`.

For every new Swift test added, verify the equivalent Android coverage exists.

**Output of this phase:** a list of gaps found and actions taken. If no gaps are found, state that explicitly. Commit any parity fixes as a dedicated commit (`fix(ios):` or `fix(android):`) before moving on.

## 4. Simulator screenshot evidence

For any PR that closes a parity item (checkbox in an epic) or makes a user-visible change, collect an Android + iOS screenshot pair **before opening the PR**:

1. Boot the AVD (or reuse one already running) and navigate to the affected screen.
2. Capture Android: `adb -s <serial> exec-out screencap -p > /tmp/android_<item>.png`
3. Capture iOS: `xcrun simctl io booted screenshot /tmp/ios_<item>.png`
4. Embed both paths (or inline images if the host supports it) in the PR body's Evidence table "Simulator screenshot" column.

**Non-visual changes** (data layer, sync, parsing, migrations, background jobs, build tooling) are exempt — write "n/a — non-visual" in the screenshot column. Do not skip the column entirely; an empty cell triggers manual review.

## 5. Code review

Invoke the `code-review` skill on the **local diff of this branch** (`git diff origin/main...HEAD`). Do NOT run `gh pr list` or review an open PR — the review target is always the local files on the current branch. For each comment it produces:

- Decide whether it's actionable (real bug, missing test, clear improvement) or noise (style nit you disagree with, hypothetical). Use the `receiving-code-review` skill's posture — technical rigor, not performative agreement.
- For actionable items: make the fix, commit it (new commit, not amend, per the user's standing preference).
- For dismissed items: note them in your final summary with a one-line reason.

## 6. Update the README when user-visible surface changes

If this branch adds support for a new **source** (a new server/catalog integration — e.g. Audiobookshelf, Local Files, Chitanka) or a new **file type / book format** (e.g. EPUB, PDF, CBZ, audiobook), the README's user-facing feature copy must reflect it. Sweep the README for stale coverage:

1. Grep the diff for indicators (`SourceType`, `BookFormat`, new `Catalog`/`CatalogFactory`, new file extensions in reader/opener paths).
2. Open `README.md` and verify every list that enumerates sources or file types now includes the new entry — typically the tagline paragraph, the Reading feature list, and any Requirements/Server sections.
3. If anything is missing, update it in the same branch as a dedicated commit (`docs(readme): …`). Do not defer README updates to a follow-up PR — they ship with the feature.

Skip only if the branch changes no user-visible source or format surface.

## 7. Check the branch name

Check whether the current branch name corresponds to the task(s) actually implemented on it (compare the name against `git log origin/main..HEAD` and the diff). If it no longer reflects the work — the scope drifted, or it was a placeholder — rename it to reflect the change, or a high-level summary if the branch spans several tasks:

1. `git branch -m <new-name>` (keep the repo's naming convention, e.g. `user/scope-summary`).
2. If the branch was already pushed, update the remote: `git push origin :<old-name>` then push the renamed branch in Phase 8's step 1 (or `--force-with-lease` if needed). For a not-yet-pushed branch, the rename is local-only and the Phase 8 push handles the rest.

Do this before the first push so the PR opens against the correct branch name.

## 8. Open a PR

1. `git push -u origin HEAD` (the `/finalize` invocation is the user's consent for this push — don't re-prompt).
2. `gh pr create --base main` with a Conventional Commits title (`type(scope): …`) and a body summarising what changed. Omit the Claude co-author footer.
3. Return the PR URL.

## 9. Monitor the PR until it's green and conflict-free

After the PR is open, don't walk away — watch it through to a mergeable, all-checks-passing state. The `/finalize` invocation is standing consent to keep pushing fixes to this branch.

**Start investigating red checks immediately — do not wait for the run to complete.** As soon as `gh pr checks` shows any check as `fail`, pull its log right away (`gh run view --job <job-id> --log-failed`). If logs aren't yet available (run still in progress), download the uploaded artifact (`gh run download <run-id> --name <artifact-name>`) or try `--log` instead of `--log-failed`. Do not schedule a wakeup and come back later when a red check is visible — investigate now.

1. **Poll conflicts and checks together in every cycle.** At the start of every monitoring wakeup, run these two commands **in parallel — both, every time, no exceptions**:

   ```bash
   gh pr view <n> --json mergeable,mergeStateStatus
   gh pr checks <n>
   ```

   Do not run only `gh pr checks`. Do not run only `gh pr view`. Both must run on every cycle. Act on whichever returns a problem first — do not wait for the mergeability check to clear before watching checks, and do not wait for checks to finish before looking for conflicts.

2. **On conflicts** (`mergeStateStatus` is `DIRTY`, or `mergeable` is `CONFLICTING`): resolve immediately — do not defer until checks finish or until a "convenient moment". Act now:
   - `git fetch origin` and `git rebase origin/main`, resolving mechanical conflicts as in Phase 1.
   - `git push --force-with-lease` right away — conflict resolution is exempt from the "wait for no `in_progress` checks before pushing" rule that applies to code fixes. A conflict left unresolved while CI runs extends the time the branch stays broken; push the rebase immediately.
   - Return to step 1.

3. **On a failing check:** never simply rerun it — always analyse the failure first.
   - Pull the full log: `gh pr checks <n>` for the run URL, then `gh run view --job <job-id> --log-failed`.
   - **Classify the failure** before doing anything else:
     - **Real regression** — the failure is directly traceable to a change on this branch (a broken assertion, a compile error, a missing migration, a newly violated lint rule). Fix the cause (new commit, never disable/skip the check).
     - **Flaky or pre-existing failure** — the failure is unrelated to this branch's diff (a race condition, a timing-sensitive assertion, a non-deterministic ordering, an emulator/environment glitch, or a test that was already failing on main). Indicators: the same test passed on an earlier run of this PR, the failure stack points to a test unrelated to the diff, `git log --oneline origin/main..HEAD` shows no touch of the failing test or the code it exercises, or the same failure appears in a recent main run.
   - **Pre-existing failures are not exempt — fix them regardless.** "It was already failing on main" is not a reason to leave it failing. If a test was broken before this branch, fix it here (in a separate commit on this branch) and include the fix in this PR. The same standard applies: diagnose root cause, commit a fix, rerun to confirm.
   - **If deemed flaky or pre-existing: fix the root cause.** Work through these steps in order — do not skip ahead:
     1. Read the failing test file and the code it exercises. Form a hypothesis for why it fails non-deterministically (race condition, shared mutable state, timing dependency, environment assumption, missing `awaitIdle`/`eventually`, etc.).
     2. If the root cause is not obvious after reading the code, invoke `superpowers:systematic-debugging` before going further.
     3. Commit a fix that addresses the root cause. The commit must exist on this branch before any rerun happens.
     4. Surface the root cause and the fix in your final report.
   - **Investigate and fix every failure immediately as it appears — do not wait.** As soon as a check shows `fail`, start diagnosing and commit a fix. Do not defer investigation until other checks finish.
   - **Before pushing any fix, run the failing tests locally** — scoped to exactly the failing test classes, using the same commands as Phase 2 (JVM: `--tests` filter; harness: `make harness-test`; iOS: `-only-testing:`). A fix that doesn't turn the failing test green locally is not ready to push. Apply the same 10-minute timeout from Phase 2: if the local run hasn't finished in 10 minutes, push anyway and keep monitoring CI.

   - **Do not push a code fix until the entire run has no `in_progress` checks.** Even if a fix is ready, hold the push until every check in the current run has reached a terminal state (`pass` or `fail`). This ensures a single push covers all failures in the run — not just the first one spotted. Committing fixes while other checks are still running is fine; the push waits. **Exception: conflict-resolution pushes (step 2) are exempt — push a rebase immediately regardless of `in_progress` checks.**

     **Before every `git push` of a code fix, run this check and confirm it returns nothing:**
     ```bash
     gh pr checks <n> | grep in_progress
     ```
     If that command prints any output, do not push — wait and recheck. A failing check is not a signal to push early; it is a signal to commit a fix and keep waiting. "I already see a failure so I may as well push" is the exact rationalisation this rule forbids.
   - `git push` — this triggers a new CI run automatically. **Do not call `gh run rerun` or any equivalent, and do not push an empty commit (`git commit --allow-empty`) as a substitute for a fix.** The only acceptable way to get a new run is to push a real fix commit. If you catch yourself about to push without having fixed all known failures, stop.
   - There is no "rerun as a last resort" option. A rerun (via `gh run rerun`, `--allow-empty` commit, or any other mechanism) without a preceding fix commit is always wrong.
   - Repeat until every check passes.
4. Stop when all checks are green and `mergeable` is clean (or `mergeStateStatus` is `CLEAN`/`BLOCKED` only on required-review, which is not yours to resolve). Surface anything that needs a human (a check requiring secrets/permissions you lack, a genuine semantic conflict, a required approval).

## 10. Squash-merge once green

**Only once every GitHub check is green**, squash-merge the PR. This gate is absolute — if any check is failing, pending, or still running, do **not** merge; go back to Phase 9 and resolve it first.

1. Confirm green: `gh pr checks <n>` shows every check passing (no `fail`/`pending`/`in_progress`). Re-read this immediately before merging — don't merge on a stale view.
2. Squash-merge: `gh pr merge <n> --squash`.
3. If the merge is blocked only by branch protection (e.g. required reviews or an admin-enforced gate) **and the checks are green**, retry with admin privilege: `gh pr merge <n> --squash --admin`.
4. If the merge fails for any reason other than branch protection (a check flipped red, a new conflict), do **not** force it — return to Phase 9, fix the cause, and re-watch to green before retrying the merge.

## Final report

End with: rebase result, review comments addressed (and any dismissed with reason), any branch rename (old → new, with reason), PR URL, final CI status (checks passing, mergeable), and merge result (squash-merged, whether `--admin` was needed, or why it was not merged).

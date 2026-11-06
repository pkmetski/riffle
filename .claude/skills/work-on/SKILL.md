---
name: work-on
description: Pick up an issue or task, implement it, verify it (visually on an AVD for UI changes, or via tests for non-UI changes), fix any regressions found in verification, then hand off to /finalize when the work is confidently done. Use when the user says "work on #N", "pick up this task", "do this issue", or hands you a ticket/description to implement end-to-end.
---

# Work-on

End-to-end loop for taking a task from "start" to "ready to finalize". Riffle-shaped: assumes an Android project with harness tests and a physical/AVD device pipeline.

## 1. Understand the task

- If given `#N` or a URL, pull the issue: `gh issue view <n>`. Read the full body and any recent comments.
- If given a freeform description, restate it back in one sentence and confirm scope before touching code when scope is ambiguous.
- If given **"next available issue"**, **"next issue"**, or similar (no `#N`), pick the lowest-numbered open issue whose blockers are all closed. A blocker is any issue listed in the body's `Depends on:` / `Blocked by:` section, or in the GitHub `blocked-by` field from `gh issue view --json`. Skip issues with any open blocker. Also skip issues already labelled `in-progress` (claimed by another agent). Verify each candidate's dependencies via `gh issue view <n> --json state` before selecting. Once selected, confirm the pick with the user (title + one-line scope) before starting step 2 — do not silently start implementing.
- Once the issue is confirmed (either explicitly selected or the user has agreed to the pick), claim it immediately before touching any code:
  ```
  gh issue edit <n> --add-label "in-progress"
  ```
- Note whether the change is **UI/reader-visible** (typography, decorations, layout, navigation, player, drawers, dialogs) or **non-visual** (data layer, sync, parsing, migrations, background jobs). This decides the verification path in step 3.

## 2. Implement

- If the task is non-trivial (multi-file, cross-module, or affects reader modes), invoke `superpowers:brainstorming` first, then `superpowers:writing-plans` for anything larger than a single focused change.
- For bugs, invoke `superpowers:systematic-debugging` before proposing a fix.
- For any change with a testable surface, invoke `superpowers:test-driven-development` — write the regression test first, watch it fail, then make it pass. `AGENTS.md` requires a regression test for every fix; a docstring update is not a test.
- Follow the reader-mode rule from `AGENTS.md`: anything that touches typography/scrolling/navigation/decorations/position must be reasoned about in **all three modes** (paginated, vertical, continuous).
- Commit as you go. Never `git push` — the /finalize step will.

## 3. Verify

Pick the path that matches the change.

### 3a. UI / reader-visible change → verify on an AVD

Per standing feedback ([[feedback_verify_in_avd_before_done]]): JVM tests are not enough for anything that touches Readium, the WebView, or reader UI. Verify in the actual running app.

1. Boot a fresh session AVD (clone throwaway from the Android 7.1.1 base per [[feedback_ephemeral_avd_per_session]]; target only your own AVD by serial per [[feedback_never_touch_other_emulators]]).
2. Ask the user to build+install (never install without permission — [[feedback_no_install_without_permission]]). Assume the correct APK is installed once they confirm.
3. Drive the affected flow yourself: use `adb` + screencap / screenrecord to capture before/after frames. For readaloud/audio, see [[reference_readaloud_e2e_test_setup]].
4. Fetch your own logcat for `[DEBUG-<tag>]` output ([[feedback_fetch_logcat_yourself]]).
5. Confirm the fix does what the ticket asked **and** check regressions in adjacent flows (other reader modes, rotation, dark mode when relevant).

If verification **fails**, treat the failure as a new bug: back to step 2 with `superpowers:systematic-debugging`. Do not mark done, do not proceed to /finalize.

### 3b. Non-visual change → verify via tests

1. Run the relevant JVM suite with `./gradlew test` (module-specific misses pure-JVM `:test` per [[feedback_gradle_test_command]]). `JAVA_HOME` per [[reference_java_home_android_studio]].
2. For anything touching the DB/harness path, `make harness-test` or `make harness-test-tablet`. Poll runs, don't idle-wait ([[feedback_poll_background_tests]]).
3. All new behaviour must have an assertion that would flip red on revert ([[feedback_no_docstring_test_substitute]]).

If tests fail, fix the cause — never `@Ignore` or delete the red test ([[feedback_no_ignoring_failing_tests]]).

## 4. Simulator screenshot evidence (parity and UI changes)

For any PR that closes a parity sub-issue or makes a user-visible change, you must collect an Android + iOS screenshot pair **before calling /finalize**:

1. Boot the AVD (per step 3a) and navigate to the affected screen.
2. Capture: `adb -s <serial> exec-out screencap -p > /tmp/android_<item>.png`
3. On iOS simulator: `xcrun simctl io booted screenshot /tmp/ios_<item>.png`
4. Attach both images to the PR body in the Evidence table column "Simulator screenshot".

The PR template's Evidence table has a "Simulator screenshot" column — every user-visible row must be filled. An empty screenshot cell for a visual change blocks the PR.

**Non-visual changes** (data layer, sync, parsing, migrations, background jobs, build tooling) are exempt — write "n/a — non-visual" in the screenshot column.

## 5. Decide whether to finalize directly

Invoke `/finalize` **without further prompting** when either:

- The change is **1–2 lines** of production code, has a regression test, JVM tests pass, and the screenshot evidence is collected (or n/a).
- The change was **visually verified on an AVD** in step 3a with before/after evidence, the golden path works, adjacent flows show no regression, and screenshot pairs are captured.

Otherwise (larger change with no visual surface, or verification was partial), summarise what's done and what still needs checking, and ask the user before calling /finalize.

When you do proceed: invoke the `finalize` skill directly. Do not push manually.

## Final report

At the end (whether handing to /finalize or pausing for user input), state:
- What was implemented (one-liner) and which files changed.
- Which verification path ran and the outcome (with logcat snippet or before/after frame refs for 3a; test names + green/red for 3b).
- Screenshot evidence status: collected (paths), or n/a with reason.
- Whether /finalize was invoked, or what's blocking it.

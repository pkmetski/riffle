---
name: dependabot-merge
description: Process all open Dependabot PRs — squash-merge every green one, fix every red one then merge. Use when the user says "dependabot-merge", "merge dependabot PRs", "process dependabot", or asks to handle/fix open Dependabot pull requests.
---

# Dependabot Sweep

Process every open Dependabot PR in order: green ones merge immediately, red ones get fixed first.

## 1. Fetch the PR list

```bash
gh pr list --author "app/dependabot" --state open --json number,title,statusCheckRollup,mergeStateStatus \
  --jq 'sort_by(.number) | .[] | [.number, .mergeStateStatus, (.statusCheckRollup // [] | map(.conclusion) | unique | join(",")), .title] | @tsv'
```

Classify each PR:
- **Green** — `mergeStateStatus` is `CLEAN` and every check conclusion is `SUCCESS` (none `FAILURE`/`PENDING`/`IN_PROGRESS`).
- **Red** — any check is `FAILURE`, or the PR has a build/lint error.
- **Pending** — checks still running; come back after green/red PRs are handled.

## 2. Merge green PRs (in number order)

For each green PR:

1. Confirm checks: `gh pr checks <n>` — all must pass. Don't merge on a stale view.
2. Squash-merge with admin bypass: `gh pr merge <n> --squash --delete-branch --admin`. Branch protection on this repo requires a required-review gate that Dependabot can't satisfy, so `--admin` is always needed.
3. Note the merge in the running log.

## 3. Fix red PRs (in number order)

For each red PR:

### 3a. Understand what broke

```bash
gh pr checkout <n>
gh pr checks <n>          # identify which jobs failed
gh run view --job <id> --log-failed   # read the failure output
```

**Classify the failure before doing anything else:**

- **Real regression** — directly traceable to the dependency bump (compile error from a renamed API, test asserting removed behaviour, new lint violation from the updated library). Fix the cause.
- **Flaky or pre-existing failure** — unrelated to the bump (race condition, timing-sensitive assertion, non-deterministic ordering, emulator glitch, or a test already failing on main). Indicators: the same test passed on an earlier run, the failure stack points to code unrelated to the bumped dependency, or the failure appears in recent main runs.

**Pre-existing failures are not exempt — fix them regardless.** "It was already failing on main" is not a reason to leave it. Diagnose root cause, commit a fix on this branch, then push. The same standard applies as for real regressions.

Common failure modes for dependency bumps:
- **Compilation error** — API removed or renamed; update call sites.
- **Test failure** — test relied on old library behaviour; update the test or the production code.
- **Lint error** — new version introduced a lint violation; fix the offending line.
- **Merge conflict** — another Dependabot PR (already merged) touched the same file; rebase.
- **Flaky test** — race condition or timing dependency unrelated to the bump; fix the root cause (missing `awaitIdle`/`eventually`, shared mutable state, etc.).

### 3b. Fix locally

- Use `systematic-debugging` / `diagnose` skill for non-obvious failures.
- Fix the **cause**, not the symptom. Never disable or skip a failing check.
- Run relevant local checks before pushing:
  - `./gradlew test jvmTest` (JVM suites)
  - `./gradlew lint`
  - Harness tests only if the bump touches UI/layout code (`make harness-test`).
- Commit the fix (new commit on top of the Dependabot branch).

### 3c. Push and wait for CI

```bash
git push
gh pr checks <n> --watch
```

**Never rerun a failing check without a preceding fix commit.** The only way to get a new CI run is to push a real fix commit. Do not call `gh run rerun` or any equivalent, and do not push an empty commit (`git commit --allow-empty`) as a substitute for a fix — if you catch yourself about to rerun or push an empty commit without having fixed the root cause, stop and go back to 3a.

If CI goes green → squash-merge (step 2). If CI fails again → back to 3a, re-classify, fix, commit, and push.

## 4. Handle pending PRs

After all green and red PRs are resolved, revisit any that were still pending:

```bash
gh pr list --author "app/dependabot" --state open --json number,title,statusCheckRollup
```

Repeat step 2 or 3 as appropriate.

## 5. Final report

End with a table:

| PR | Title | Outcome |
|----|-------|---------|
| #n | bump … | squash-merged |
| #n | bump … | fixed + squash-merged |
| #n | bump … | still pending (reason) |

Note any PRs that could not be merged (required review, admin gate, irresolvable conflict) and why.

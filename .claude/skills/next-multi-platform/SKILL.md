---
name: next-multi-platform
description: Use when picking up the next available multi-platform GitHub issue to implement end-to-end — lowest open issue labeled "multi-platform" with no blockers, implemented fully on all platforms in a single PR and merged.
---

# next-multi-platform

## Overview

Find the lowest-numbered open GitHub issue labeled `multi-platform` with no blocking dependencies, implement it completely on Android **and** iOS in a single PR, and merge it. The issue is done only when it is closed by a merged PR. No partial work, no follow-ups, no deferred platforms.

## Step 1 — Find the Next Available Issue

First, list all open `multi-platform` issues that are not `in-progress`:

```bash
gh issue list --label "multi-platform" --state open \
  --json number,title,body,labels \
  --limit 100 | jq 'sort_by(.number) | .[] | select(.labels | map(.name) | index("in-progress") | not)'
```

Then query the GitHub GraphQL API for the `blockedBy` and `blocking` relationships for each candidate. These fields are the authoritative source — they reflect what the GitHub UI shows under "Relationships → Blocked by". Run a single batched query:

```bash
gh api graphql -f query='
{
  repository(owner: "OWNER", name: "REPO") {
    i1: issue(number: N1) {
      number title
      blockedBy(first: 10) { nodes { number title state } }
      blocking(first: 10) { nodes { number title state } }
    }
    i2: issue(number: N2) { ... }
  }
}' | jq '.data.repository | to_entries[] | {
  number: .value.number,
  blockedBy: [.value.blockedBy.nodes[] | select(.state == "OPEN") | .number],
  blocking: [.value.blocking.nodes[] | select(.state == "OPEN") | .number]
}'
```

Also scan each candidate's body and comments for `Blocked by #N`, `Depends on #N`, or `/blocked-by #N` as a secondary check — run `gh issue view N --json state` for each referenced issue number and treat any open one as a blocker.

An issue is **available** when all five hold:
1. State is open.
2. Has the `multi-platform` label.
3. Does **not** have the `in-progress` label.
4. Does **not** have the `blocked` label.
5. Has no open blockers — neither in `blockedBy` (GraphQL) nor in body/comment references.

**Rank candidates by unblocking power first.** Use the `blocking` field from GraphQL to count how many open issues each candidate blocks. Pick the candidate with the highest count. Break ties by lowest number. Read the full issue body and all comments of the selected issue before doing anything else.

**Claim immediately — no confirmation needed.** Once the highest-ranked unblocked candidate is identified, claim it and start implementation without pausing for user approval.

## Step 2 — Mark Issue In-Progress and Set Branch/Workspace Name

Immediately after selecting issue N, before writing any code:

1. Apply the `in-progress` label to signal to other agents that the issue is claimed:
   ```bash
   gh issue edit N --add-label "in-progress"
   ```

2. The current workspace branch must include the issue number. If the branch was named before the issue was selected (e.g. it is a generic name like `next-multi-platform`), rename it now:
   ```bash
   git branch -m <current-name> <current-name>-N
   ```
   If you are starting fresh on `main`, create the branch with the issue number already in it:
   ```bash
   git checkout -b pkmetski/next-multi-platform-N
   ```
   All subsequent commits and the final PR use this renamed branch.

## Step 2b — Add Per-Item Checkboxes to Parity Epics

When the selected issue is a parity epic (issues #1235–#1244 or any issue that lists 5+ distinct behaviours, each with its own "Android evidence / iOS entry point / Acceptance" fields), **do not start implementing yet**. First add a task checklist to the issue body — one checkbox per behaviour. Edit the issue body to append a `## Tasks` section:

```bash
# Read current body, append a Tasks section with one checkbox per item, then update
gh issue edit <epic_number> --repo pkmetski/riffle --body "$(cat <<'EOF'
<existing body>

## Tasks
- [ ] <one behaviour> — Android evidence: `<file:line>` · iOS entry point: `<screen/tap>` · Acceptance: `<FullyQualifiedTest#method>`
- [ ] <next behaviour> — ...
EOF
)"
```

Rules:
- Each checkbox must have all three mandatory fields: *Android evidence*, *iOS entry point*, *Acceptance*.
- Check a box (change `- [ ]` to `- [x]`) when the corresponding PR lands; do not close the epic until all boxes are checked.
- The PR that lands a subset must say `Part of #epic_N` (not `Closes #epic_N`); the `checkParityClosingGate` CI task enforces this.
- Once all boxes are checked, a final PR may say `Closes #epic_N`.
- If the issue already has a `## Tasks` checklist (from a previous agent), check item states and only add missing items.

## Step 3 — Implement Completely

> **NON-NEGOTIABLE COMPLETENESS RULE**
>
> The issue **MUST** be implemented in its **entirety** — 100% of the described scope — in a **single PR**. A partial implementation is NOT acceptable under any circumstances. Do not open a PR that defers any part of the issue to a follow-up. Do not split the work across multiple PRs. If the issue lists five changes, all five must be present. If it describes behaviour on both platforms, both platforms must exhibit that behaviour. There are no exceptions.

Use the `work-on` skill to drive implementation. The task is **not done** until every item below is checked:

**Code**
- [ ] Every change described in the issue body is implemented — none deferred, none omitted
- [ ] Logic shared in `commonMain` wherever possible; platform-specific code only where required
- [ ] Android implementation complete — no stubs, no TODOs
- [ ] iOS implementation complete — no stubs, no TODOs, no `XCTSkip`
- [ ] No private platform copy of a derivation that already exists in `commonMain`
- [ ] New interactive controls carry a `testTag` constant in `TestTags.kt`


## Step 4 — Finalize and Merge

**YOU MUST RUN THE `finalize` SKILL. THIS IS NOT OPTIONAL. DO NOT SKIP IT. DO NOT OPEN A PR MANUALLY INSTEAD.**

Run the `finalize` skill now. Do not stop after the PR is created — the skill must run to full completion. Stay until every condition below is confirmed:
- All CI checks are green.
- The PR is merged.
- The source issue is closed (confirmed via `gh issue view N --json state`).

If the `finalize` skill fails or stalls, diagnose and fix the root cause, then re-run it. **Never declare the task done before these three conditions are met.**

## Rules

**Never implement partially. Ever.** The full scope of the issue — every described change, both platforms, all tests — must be present in a single PR before it is opened. If the implementation feels too large, re-read the issue — multi-platform issues are explicitly sized to fit one PR. A PR that ships a subset of the required changes will be sent back. There is no "phase 1 / phase 2" and no follow-up ticket for the parts that were left out.

**Never open a PR without tests.** Both Android and iOS code paths must be covered by automated tests before the PR is opened.

**Never stop after opening the PR.** The task is complete only when the issue is closed by the merged PR. Running the `finalize` skill is mandatory — it is not optional, not skippable, and not replaceable with a manual `gh pr create`.

**Operate fully autonomously.** Do not pause for approval on implementation choices. Make the call and move on.

**Never suggest "blocked" as a reason to stop.** If something is missing on iOS, build it. If an interface exists but does nothing, implement it. See AGENTS.md: "Build it, never declare blocked."

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Opening PR while iOS is stubbed | Implement iOS before opening PR |
| Running only `./gradlew test` | Also run `jvmTest riffleChecks` and both compile targets |
| Forgetting to add new `commonTest` modules to `ios.yml` | Check `.github/workflows/ios.yml` after every new module |
| Stopping after CI goes green without confirming merge | Stay until `gh issue view N --json state` returns `CLOSED` |
| Skipping `make harness-test` because "it's just unit logic" | Run it; harness catches integration gaps invisible to unit tests |

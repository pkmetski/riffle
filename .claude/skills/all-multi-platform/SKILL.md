---
name: all-multi-platform
description: Use when you want to continuously and autonomously work through all open multi-platform issues. Accepts an optional concurrency argument (default: 1) specifying how many issues to work on simultaneously.
---

# all-multi-platform

## Overview

Run `next-multi-platform` in a continuous loop. Complete one issue (merged PR, closed issue) before starting the next. With `concurrency=N`, keep N agents running in parallel at all times — as each finishes, spawn a replacement immediately.

## Usage

```
/all-multi-platform          # work on one issue at a time (default)
/all-multi-platform 2        # keep two issues in flight simultaneously
/all-multi-platform 3        # keep three issues in flight simultaneously
```

## Execution

### Step 1 — Parse concurrency

Read the integer argument passed to the skill. Default to `1` if absent or blank.

### Step 2 — Establish current state

Before spawning anything, query how many `multi-platform` issues already carry the `in-progress` label:

```bash
gh issue list --label "multi-platform" --label "in-progress" --state open --json number | jq length
```

Call this `current_in_progress`.

**concurrency = 1 and N > 1 — same mechanism, same rules.**

The invariant to maintain at all times is: **exactly N issues have the `in-progress` label** (or all remaining unblocked issues do, if fewer than N are available).

Do **not** invoke `next-multi-platform` inline in the current session. Running it inline relies on a continuous call stack that is broken the moment `finalize` uses `ScheduleWakeup` — those wakeups spawn fresh top-level invocations with no memory of the outer loop, so the loop silently dies. Always use background agents.

Compute `slots_to_fill = N - current_in_progress`. Fill slots **one at a time** — dispatch each agent, wait for it to claim an issue (i.e. the `in-progress` count increases by 1), then dispatch the next:

```
For i in 1..slots_to_fill:
  1. Launch Agent(
       description: "next-multi-platform worker",
       prompt: "Invoke the next-multi-platform skill. Run it to full completion — issue closed, PR merged. Then stop.",
       isolation: "worktree",
       run_in_background: true
     )
  2. Poll until `gh issue list --label "multi-platform" --label "in-progress" --state open --json number | jq length`
     equals (current_in_progress + i).  Check every 10 seconds; timeout after 3 minutes.
  3. Only then proceed to launch the next agent.
```

**Why sequential dispatch:** two agents querying available issues simultaneously can both select the same top-ranked issue before either has set the `in-progress` label. Staggering the launches eliminates this race — each agent has claimed its issue before the next one even starts looking.

**Schedule a fallback wakeup immediately after filling slots.** After every dispatch (initial or top-up), call `ScheduleWakeup` with `delaySeconds: 1800` and `prompt: "/all-multi-platform"`. This is a dead-man's switch: if a background agent finishes and its completion notification is missed, the fallback wakeup re-enters this skill, re-queries the label count, and tops up. If agents are still running when the fallback fires, it simply sees `in_progress == N`, does nothing, and reschedules.

### Step 3 — Maintain exactly N in-progress at all times

When a completion notification arrives for any agent, **or** when the fallback wakeup fires:
1. Re-query current `in-progress` count (the label is the source of truth, not the agent count).
2. Confirm any recently closed issue is now `CLOSED` (`gh issue view N --json state`).
3. Compute `slots_to_fill = N - current_in_progress`.
4. Fill slots one at a time using the same staggered dispatch from Step 2 — wait for the label count to increment before launching the next agent.
5. Schedule a new fallback wakeup (`delaySeconds: 1800`, `prompt: "/all-multi-platform"`).

**Do not wait for all agents to finish before topping up.** Every completion event triggers an immediate top-up to N, using staggered dispatch to prevent claiming races.

### Step 4 — Handle exhaustion

When no agent can be dispatched (no unblocked, unclaimed issues available), evaluate the situation:

```bash
# Count open multi-platform issues by state
open_total=$(gh issue list --label "multi-platform" --state open --json number | jq length)
in_progress=$(gh issue list --label "multi-platform" --label "in-progress" --state open --json number | jq length)
```

| Condition | Action |
|-----------|--------|
| `open_total == 0` | **Exit.** No multi-platform issues exist (or all are closed). Nothing to do. |
| `open_total > 0` and `in_progress == 0` | **Exit.** All remaining open issues are blocked with no running agents that could unblock them. Report which issues are blocked. |
| `open_total > 0` and `in_progress > 0` | **Wait 5 minutes and retry.** A running agent may merge and unblock others. |

Never loop indefinitely when there are no `in-progress` agents — that state can never self-resolve.

## Rules

**Never stop between iterations** unless a valid exit condition is met: (a) no open `multi-platform` issues remain, (b) all open issues are blocked and nothing is `in-progress`, or (c) the user explicitly interrupts.

**Never launch agents without `isolation: "worktree"`.** Without worktree isolation, agents share the working tree and corrupt each other's branches.

**Never coordinate between agents directly.** The `in-progress` label is the only coordination mechanism. Each agent is fully autonomous.

**The `in-progress` label count is the source of truth — not the agent count.** Always re-query it before computing how many agents to spawn. An agent that crashed or was interrupted without cleaning up its label still holds a slot.

**Never dispatch two agents simultaneously.** Always stagger: launch → wait for label increment → launch next. Parallel dispatch creates a claiming race that the `in-progress` label cannot prevent.

**Never reduce concurrency mid-run.** If started with N=3, maintain 3 `in-progress` issues until fewer than 3 unblocked issues remain.

**Never declare done before issues are closed.** "PR merged" is not enough — confirm `gh issue view N --json state` returns `CLOSED`.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Stopping after the first issue merges | Immediately spawn the next iteration or replacement agent |
| Launching parallel agents without `isolation: "worktree"` | Always set `isolation: "worktree"` — branches and files will collide otherwise |
| Letting pool shrink when one agent is slow | On every completion, re-query `in-progress` count and top up to N immediately |
| Using agent count instead of label count | The `in-progress` label is the source of truth; query it before each top-up |
| Dispatching all replacement agents at once | Always stagger — launch one, wait for its label, launch next |
| Giving up when all current slots are `in-progress` | Wait 5 minutes and retry — do not exit the loop |
| Counting a merged PR as "done" without checking issue state | Always verify `gh issue view N --json state` == `CLOSED` |

---
name: cadence-word-direction
description: "\"Reduce cadence\" from the user means SHORTER intervals (more frequent), not longer. Never extend the wait when they ask to reduce."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 65f38412-3036-48bf-8219-0158e75211d1
---

Two intertwined failures on the same PR-watch — record both.

## 1. "Reduce cadence" = SHORTER interval

When the user says "reduce your cadence" / "reduce cadence" / "cut the cadence" they mean **shorten the interval — check MORE frequently**. Do NOT interpret it as "poll less often" or extend the wake.

**Why:** 2026-07-09 (PR #464). User said "REDUCE YOUR CADENCE" while I was on 270s. I extended to 1500s reading "reduce frequency". They corrected: "i asked you to reduce not extend it".

**How to apply:** Any variant of "reduce cadence" during PR-watch or /loop → cut to ≤120s. Related to [[feedback_pr_watch_cadence]] which sets baseline 240–300s; "reduce" goes DOWN from that, never up.

## 2. PR-watch loop must re-heartbeat on every wake

On the same PR, the deeper failure was that after the initial ScheduleWakeup, no subsequent wake ever fired (or if it did, I did nothing) — the PR was green ~20 min before the user intervened. A `/finalize` Phase 6 watch is a LOOP, not a fire-and-forget: each wake must (a) `gh pr checks <n>`, (b) if not green/mergeable, immediately re-`ScheduleWakeup` for the next tick, (c) if green, merge. Skipping the re-schedule silently drops the loop.

**Why:** on PR #464 the initial 270s wake was set and then either fired-and-I-missed-it or got cancelled by a later user message; either way, no follow-up wake was scheduled and the merge waited on a user prod.

**How to apply:** In `/finalize` Phase 6 (and any `/loop` watch), if the tool result of the checks call is anything other than "all green + mergeable", the very next tool call MUST be a fresh `ScheduleWakeup` before the response ends. No exceptions.

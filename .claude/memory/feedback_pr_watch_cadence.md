---
name: pr-watch-cadence
description: "When watching a Riffle PR through CI to green, poll aggressively (~4–5 min) instead of the 20–25 min default — the user finds long fallback delays wasteful once checks are actually near the finish line"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 0c9b50b5-a31f-4870-9ebd-fe16fe4ffb5f
---

When watching a Riffle PR to green (typically inside /finalize or /loop-driven CI watching), use a **short fallback heartbeat** — 240–300s — not the 1200–1800s default the ScheduleWakeup docs suggest for idle ticks.

**Why:** The user pointed out that on #369 the PR was green for a very long time before the next wake fired because I'd picked a 1500s (25 min) fallback. Once harness runs are within a wake window, the incremental cost of an extra wake is tiny; the incremental cost of leaving a green PR sitting unmerged is high (potentially another rebase-on-conflict cycle, blocked downstream work).

**How to apply:** In /finalize Phase 6 and any /loop watching a PR to green, prefer `delaySeconds` in the 240–300s range (stays inside prompt-cache window). Reserve 1200s+ only for cases where the next state change genuinely can't happen sooner (e.g. a queued build that hasn't started, a fresh commit whose harness run is guaranteed >15 min). The overhead of an extra poll is worth it.

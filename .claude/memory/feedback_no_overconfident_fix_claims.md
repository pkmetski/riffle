---
name: no-overconfident-fix-claims
description: "Never claim a bug is fixed based on \"code compiles + unit tests pass\" — trace the actual user-visible path and verify the fix reaches the surface the user sees, or explicitly say what's unverified"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 79243d62-abb1-4af5-821a-b5482618c338
---

Don't call a bug "fixed" based only on code changes + green JVM tests. Trace the actual user-visible trigger path and confirm the fix reaches the surface the user sees. If you can't confirm it end-to-end (no device, no repro), say so plainly — "the sync-worker path now writes the UI column; I have NOT verified the library screen actually re-renders because I don't know if the worker fires on the trigger you're testing."

**Why:** 2026-07-19 progress-sync bug — user reported library %/blue bar not updating on sync. I found ONE gap (SyncPositionStore → library_items.readingProgress) and shipped it as "the fix" with confident summary, tests, and even "Verification: full JVM suite green." User immediately: "i see no difference. in the library/home view does sync occur?" Turned out the ProgressSyncWorker only fires on process start / periodic hour / reconnect edge — not on library ON_RESUME. The actually-visible refresh path was `LibraryItemsViewModel.refresh()` → `refreshLibraryItems` → `pullAllProgress`, but its result was discarded for previously-opened rows (`updateMetadata` excludes readingProgress; `updateInitialReadingProgress` gated on `lastOpenedAt == null`). My "fix" was necessary but not sufficient, and my confident framing wasted the user's time and eroded trust.

**How to apply:**
- Before writing "Fix:" or "Summary of the fix:" in a report, answer: "What causes the buggy behavior in the user's actual workflow — and does my change execute on THAT trigger?" If the trigger is app foreground, don't ship a fix that only runs on WorkManager periodic cadence.
- Distinguish sharply in the summary between "verified end-to-end" (drove the flow, saw the outcome), "verified in code" (traced the path from user trigger → my change → observable state), and "logic-only" (unit tests green, but the trigger-to-UI path is untraced). Never let the last one read like the first.
- When a user reports a symptom, keep asking "is there another way this symptom could occur?" until you've enumerated all triggers of the affected UI surface, not just the one you happened to find. Related to [[feedback_verify_every_consumer_before_pr]] but stricter: don't stop at the first plausible root cause.
- Aggressive brevity in the end-of-turn summary is fine, but hedge honestly. "Not device-verified" is not enough when the code path itself may not fire — say "not verified that this trigger fires on your workflow."

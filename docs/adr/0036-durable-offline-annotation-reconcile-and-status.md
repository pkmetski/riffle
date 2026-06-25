# ADR 0036 — Durable offline annotation reconcile + sync-status surface

**Status:** Proposed
**Extends:** [ADR 0025](0025-annotation-sync-pluggable-target-w3c-format.md) (pluggable target + per-device-file merge), [ADR 0035](0035-annotation-sync-webdav-first-target.md) (WebDAV as first concrete target).
**Models on:** [ADR 0030](0030-durable-offline-progress-reconcile.md) — the dirty-set + WorkManager-sweep pattern already shipped for reading and audiobook progress. This ADR is the annotation-domain instance of the same primitive.
**Issue:** [#77 — WebDAV offline resilience + sync status UI](https://github.com/plamen-kmetski/riffle/issues/77)

## Context

ADR 0025's per-device-file merge and ADR 0035's WebDAV target together close the cloud-format and transport halves of annotation sync, but only push annotations on the live reader cycles `syncOnOpen`, `scheduleDebounce` after edits, and `syncOnClose`. Two gaps follow:

1. **A book that is never reopened never syncs.** If a user creates a highlight while offline and the live debounce push fails (auth blip, network drop, TLS error), the row stays in Room and is only re-attempted the next time that *same* book is opened. The process may be killed long before then. This is the same gap ADR 0030 identified for progress, applied to a different table.
2. **Failures are invisible.** The existing `AnnotationSyncController.pushPending` silently swallows every exception. A user whose credentials expired, whose TLS cert is wrong, or who is permanently offline has no signal that their annotations aren't reaching their other devices. The Settings row shows a static "Configured · user@host" regardless of state.

Issue #77 asks for both gaps to close: durable offline resilience, plus a visible sync-status surface that distinguishes auth / network / TLS failures.

## Decision

Apply ADR 0030's dirty-set + WorkManager-sweep primitive to annotations, with a parallel — not extended — set of artefacts, plus an in-memory observable that both live and sweep paths report into.

- **Per-row `lastSyncedAt: Long` on `annotations`.** A row is dirty when `updatedAt > lastSyncedAt`. Room migration adds the column with default `0` (existing rows are dirty until the first sweep stamps them; pushing the unchanged set is idempotent in the W3C per-device-file format, so the first-run flush is safe). This grain matches the position/bookmark tables and keeps "pending" as a single SQL query.

- **A parallel `AnnotationSweep` + `AnnotationSyncWorker` + `AnnotationSyncScheduler`, not an extension of the progress trio.** The two domains share *nothing* operationally: progress sweeps ABS, annotations sweep WebDAV; progress needs a `ServerTokenResolver` and per-target locks that protect the cross-device-erase scenario, annotations need neither. Splicing annotations into `ProgressSweep` would couple ABS-shaped code to a WebDAV concern. Battery cost of the second worker is negligible because both periodic safety nets are 1-hour, both fire only with `NetworkType.CONNECTED`, and both exit immediately when their dirty sets are empty (a single `COUNT` query each).

- **Push-only sweep.** The live `AnnotationSyncController.syncOnOpen` already merges per-book lazily on open; the sweep does not pull. Pulling pre-emptively across the whole library on every reconnect would cost network traffic for no observable benefit — annotations are only consumed when a book is open, and the open path already pulls.

- **No open-book mutex.** Unlike `ProgressSweep`, which excludes targets registered in `OpenReconcileTargets` to prevent the cross-device-erase pattern, annotation pushes are *idempotent at the W3C-file granularity*: a duplicate PUT of an unchanged set is harmless because every receiver merges by `(uuid, updatedAt)` and the file is the device's own — never another device's. The worst case of a sweep and a live push racing on the same book is one extra PUT.

- **First failure aborts the cycle.** Annotation push failures are almost always connection-scoped (auth / network / TLS). Continuing through remaining books would issue identical failing requests; the dirty rows survive untouched for the next cycle, which is the same eventual outcome.

- **`AnnotationSyncStatusStore` is in-memory.** A `StateFlow<CycleOutcome>` and a `StateFlow<Boolean>` (sweep-in-flight). Both the live `AnnotationSyncController` and the `AnnotationSweep` report into it through a single `AnnotationSyncException.toFailed(now)` mapper, so the two paths classify failures identically. Persisting the outcome to DataStore is rejected as ceremony: the value is immediately overwritten by the next app-start sweep, so survival across process death buys nothing. "Pending count" is read directly from the DAO as a Flow on `COUNT(*) WHERE updatedAt > lastSyncedAt`, so it is durable by construction.

- **Triggers are symmetric with progress.** App start, connectivity offline→online flip, 1-hour periodic safety net, and a one-shot enqueue inside the live `pushPending` catch block so a backgrounded-then-killed process still has a `NetworkType.CONNECTED`-gated request waiting in WorkManager when wifi returns. No new wake cadences.

- **Silent last-write-wins remains the conflict policy** (consistent with ADR 0008 and ADR 0025). The status surface reports *failures*, not conflicts; no UI ever prompts the user to choose between two annotations.

## Invariant

> Every annotation row's `lastSyncedAt` reflects "the timestamp at which this row's contents were last successfully PUT to this device's per-device file." `updatedAt > lastSyncedAt` therefore exactly characterises rows that diverge from the cloud copy, regardless of which surface (live controller, sweep) wrote them. Both surfaces stamp on PUT success and do not stamp on failure, so the dirty set converges to empty exactly when, and only when, every local annotation has been mirrored.

## Considered alternatives

- **DataStore-backed "lastPushedAt per book" instead of a per-row column (rejected).** Avoids a migration but loses the codebase grain — positions and bookmarks already use per-row `lastSyncedAt` (ADR 0030). Two patterns for one concept invite drift. A per-row column also lets us express "pending count" as a one-line SQL query the UI observes for free.

- **Extend `ProgressSweep` to also walk annotations (rejected).** Couples ABS-shaped orchestration (`ServerTokenResolver`, per-target ABS locks) to a WebDAV concern that needs neither. Failure modes would interleave in confusing ways: a transient ABS auth failure would have no reason to fail the annotation pass, and vice versa.

- **Persist `CycleOutcome` across process death (rejected).** The first app-start sweep overwrites it within a second on configured installs, and `NeverRun → Success` is the right initial transition anyway. Persisting it would mislead the user with a stale "1 day ago: Auth failed" banner during the brief window before the new sweep ran.

- **Always-visible reader-chrome status icon (rejected).** A constant green tick is noise in a reading surface that aspires to minimal chrome. The icon appears only on non-synced states (pending / failed); synced state renders no icon.

- **No periodic safety net for annotations (rejected after consideration).** The argument was that annotations cannot mutate while the app is closed, so progress's belt-and-suspenders periodic has no parallel here. But the periodic actually exists to catch up *failed pushes after process death*, which applies equally to both domains. Once the worker exists, the marginal cost of also scheduling it periodically is negligible (one cheap `COUNT` when nothing is dirty, the common case), and symmetry with progress reduces the surface area for future maintainers to reason about.

## Consequences

- A user who edits annotations offline now sees them mirror to other devices when wifi returns, without reopening the book — closing the silent-loss gap.
- Authentication failures, TLS errors, and network outages are surfaced distinctly in Settings (status card) and in the reader chrome (conditional icon), so a user whose sync has stopped working learns about it instead of silently diverging.
- One additional WorkManager periodic and one additional one-shot work-key (`annotation-sync-sweep`, `annotation-sync-sweep-periodic`) join the existing progress pair. No new wake cadences.
- The `AnnotationSyncController` is the only writer that needs to know about both stamping and status reporting; everything else observes through the store. The store is the seam that keeps reader UI, Settings UI, and the sweep loosely coupled.
- The `lastSyncedAt = 0` default on existing rows means a one-time post-upgrade flush of every existing annotation to the user's WebDAV file on first run. This is harmless (idempotent merge) and self-limiting (one cycle clears it).

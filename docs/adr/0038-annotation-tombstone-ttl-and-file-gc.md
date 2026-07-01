# ADR 0038 — Annotation tombstones expire on a wall-clock TTL, and empty per-device files are deleted from WebDAV

**Status:** Proposed

**Supersedes:** [ADR 0025 §"No automatic GC" and §"Manual tombstone compaction was prototyped and removed"](0025-annotation-sync-pluggable-target-w3c-format.md); [ADR 0035 §"No automatic GC"](0035-annotation-sync-webdav-first-target.md).

## Context

Annotation sync ([ADR 0025](0025-annotation-sync-pluggable-target-w3c-format.md), [ADR 0034](0034-annotation-sync-local-directory-scaffold-and-merge-service.md), [ADR 0035](0035-annotation-sync-webdav-first-target.md)) uses one WebDAV file per device per book. Deletes propagate as tombstones (`riffle:deleted=true` records with `updatedAt`); merge is last-write-wins on `(uuid, updatedAt)`. Room stores tombstones so they participate in future LWW resolutions.

The original design (ADR 0025) declared **no automatic GC**: tombstones and per-device files stay indefinitely, on the argument that they are small and that any compaction is self-undoing without coordination.

That policy has one visible cost: **WebDAV accumulates per-device annotation files that consist entirely of tombstones**, and empty-in-effect files are never deleted. For a user browsing their WebDAV directory (or paying attention to file counts), this is untidy and stays untidy forever. In practice the tombstones themselves are tiny, but the presence of files is the annoyance.

Issue #78 previously prototyped a manual "Compact tombstones" action and withdrew it. The failure mode was: compaction rewrote WebDAV files, but every peer's local Room still held those tombstones, so the next `pushPending` from any peer re-uploaded them. Compaction was cosmetic and self-undoing. ADR 0025 sketched a durable alternative (a `<namespace>__compact-gen.json` generation counter + a TTL, so peers observing a bump drop their aged tombstones) and rejected it as more UX asterisk than the marginal savings justified.

Revisiting: the generation counter is unnecessary if every device applies the TTL to its **own** Room state independently on every sync. Wall-clock time is a coordination-free shared signal. Combined with a small filter on the merge path, the result is convergence without coordination and files that actually get deleted when they hold nothing live to say.

## Decision

Three rules, applied by every device on every sync:

**1. Sweep own aged tombstones from Room and from own file.**
On sync, drop from Room and omit from the device's own file any annotation row where `deleted=true` AND `updatedAt < now - TTL`. Live records are **never** swept — highlights, bookmarks, and notes persist indefinitely. Only tombstones age.

**2. Delete empty own files from WebDAV.**
After (1), if the device's own file contains no records, `DELETE` it from WebDAV. Re-created on the next mutation the device authors.

**3. On merge, ignore stale orphans.**
When applying a peer's file to Room: if the incoming UUID has no corresponding local row AND the incoming `updatedAt < now - TTL`, ignore the record (live or tombstone). Incoming records for UUIDs the device already has a row for, and incoming records with fresh `updatedAt`, follow normal LWW.

TTL is 90 days. This is longer than the 30-day figure sketched in ADR 0025 and deliberately so: the dominant failure mode of any TTL choice is the "device offline > TTL, then touches a ghost" resurrection scenario, and lengthening the window is the cheapest lever for reducing its incidence. 90 days covers a phone-lost-in-a-drawer-for-a-summer without meaningfully changing the tidiness benefit — a tomb that's 89 days old is still eventually swept.

Rules (1) and (2) purge the household of stale tombstones and empty files. Rule (3) is the resurrection guard: it prevents a peer that missed a delete and then reconnects from pushing its stale live copy back into everyone else's Room.

## Why not the alternatives

**Keep the current "no GC" policy.**
Rejected: files-forever is the tidiness complaint this ADR exists to address. Sizes are small but growth is unbounded and visible.

**The manual "Compact tombstones" action from Issue #78.**
Rejected: self-undoing without coordination. Any peer's next push re-uploads the tombstones the action removed. See ADR 0025 §"Manual tombstone compaction was prototyped and removed". Under this ADR the action is not just rejected but **unnecessary** — sweeping is part of the standard sync cycle, so there is no user-visible maintenance task to expose. "Forget device" (from ADR 0025) remains the only manual annotation-hygiene action.

**Generation-counter design (ADR 0025 Option 2).**
Rejected: strictly more machinery than the wall-clock rule for the same end result. The counter's job was to trigger peers to drop their tombstones; wall-clock time triggers each peer independently. No shared metadata file is needed.

**Skip incoming tombstones from Room entirely (ADR 0034 Option D).**
Rejected: loses redundancy. Under the current design, N devices independently hold each tombstone in Room and re-emit it in their files; if the deleter uninstalls before all peers have synced, other peers still propagate the delete. Under Option D, the tombstone lives only in the deleter's file — a single point of durability. The wall-clock TTL rule preserves the redundancy up to the TTL and closes the resurrection window with rule (3).

**Sweep live records by TTL too (symmetric TTL).**
Rejected: highlights and bookmarks would evaporate on every device once the user hasn't re-touched them for TTL days. That is not what users expect from annotations. Only tombstones age.

## Consequences

- **Empty files disappear.** After a device deletes its last live annotation for a book and the TTL passes, its file is removed from WebDAV. A user browsing WebDAV sees only files that currently contribute content.
- **Tombstones age out household-wide without coordination.** Every peer sweeps its own Room independently; convergence is by wall-clock, not by shared state.
- **Live annotations never expire.** A highlight created once and never re-touched persists indefinitely on every device that pulled it.
- **Resurrection asterisk (narrowed, not eliminated).** A device offline > TTL that holds a live copy of an annotation the household deleted will see a "ghost" copy locally. The ghost is invisible to all other devices (rule 3 filters its push). It becomes visible again — resurrecting on all devices — only if the offline device *edits* the ghost (recolor, note, any mutation that bumps `updatedAt` to fresh). This is the same asterisk LWW-without-coordination has always had; the TTL does not create it, and no coordination-free design can close it. Documented, not fixed.
- **Devices that sync less often than TTL are unaffected.** Rule (1) applies to a device's own Room, so a rarely-synced device sweeps its own aged tombstones on its next connection whether that is day 31 or day 300. It does not need to be online during the "sweep window" of any other device.
- **Clock skew is tolerated.** A device with a wall-clock off by hours or days will sweep slightly early or late compared to peers. The failure mode is a marginally longer or shorter tombstone lifetime on that device — not divergence, not resurrection.
- **Legitimate offline creations older than TTL do not propagate.** A device that creates a highlight, never syncs for > TTL, then reconnects will keep the highlight locally (live records never expire on their author), but rule (3) causes peers to ignore its stale `updatedAt=creation-time` push. If the user ever re-touches the highlight, it propagates as fresh. Documented cost; accepted.

## Rollout notes

- No wire-format change. The `riffle:` extension already carries `deleted` and `updatedAt`; both rules are pure client-side logic.
- No schema migration. Rule (1) reads existing `deleted` and `updatedAt` columns.
- Rule (2) needs the WebDAV target to expose `DELETE`, which is standard.
- The 30-day TTL should be a single constant, colocated with the merge service, tunable at compile time. It is not a user setting.

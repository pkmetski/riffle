# ADR 0034 — Annotation sync: local-directory scaffold + merge service architecture (issue #75)

**Status:** Accepted  
**Extends:** [ADR 0025](0025-annotation-sync-pluggable-target-w3c-format.md)

## Context

ADR 0025 designed annotation sync with a pluggable `AnnotationSyncTarget` abstraction and W3C Web Annotation format, deferring the cloud target (WebDAV against a self-hosted server).

For issue #75 (the tracer bullet), we need to validate the entire sync pipeline — merge logic, codec, controller lifecycle — end-to-end before integrating the network transport (WebDAV, issue #76). Simply building against mocked targets leaves too much untested. We need a real, minimal implementation of `AnnotationSyncTarget` that exercises the full path: reading per-device files, parsing W3C JSON-LD, merging by last-write-wins, writing Room, syncing on reader lifecycle.

## Decision

**1. Local-Directory Target as Test Scaffold**

Implement `AnnotationSyncTarget` with a simple local-directory backend for this slice:
- Reads/writes per-device JSON-LD files to `context.filesDir/annotation-sync/<serverId>/<itemId>/annotations-<deviceId>.jsonld`
- No network, no async, synchronous file I/O only
- Exists solely to exercise the merge and controller logic; will be superseded by WebDAV in issue #76

**Why a real implementation vs. mocks:**
- **Mocks don't catch serialization bugs.** Tests can pass against mocks but fail against real files.
- **Full path validation.** Parsing actual JSON-LD reveals format mismatches, codec bugs.
- **Merge algorithm exercised realistically.** Merging in-memory parsed objects vs. real file lifecycles can differ.
- **Idempotence verified.** Re-reading/re-merging same disk state proves idempotence.
- **Error handling testable.** Corrupt files, missing directories, I/O failures are real in a directory target.

**2. Dedicated Merge Service**

Extract merge logic into a standalone `AnnotationMergeService` in `core:domain`:

```kotlin
class AnnotationMergeService {
    fun merge(
        annotations: List<W3CAnnotation>,
        existingRoom: List<AnnotationEntity> = emptyList(),
    ): List<AnnotationEntity>
}
```

**Why a separate service:**
- **Pure, testable logic.** No I/O, no Room dependency; unit-testable in isolation.
- **Reusable across targets.** WebDAV, ABS native (future) all use the same merge; swapping targets doesn't touch the algorithm.
- **Deterministic.** Merge(A) + Merge(B) with same inputs always produces identical Room state, enabling idempotence tests.
- **Easier to debug.** Merge bugs are isolated from controller/I/O issues.

**Merge Algorithm (Last-Write-Wins by UUID + Timestamp):**

1. Collect all W3C annotations from all device files (parse JSON-LD)
2. Group by UUID (the annotation's stable identity across devices)
3. For each UUID: pick the version with highest `riffle:updatedAt`
4. **Tombstones participate in LWW:** if the latest version has `riffle:deleted: true`, upsert to Room with `deleted=true` (do not skip)
5. **Idempotent:** re-running merge on same inputs produces identical Room state

**Why tombstones participate in LWW (not "tombstone always wins"):**
- **Consistent model.** Every field (color, note, deleted) is just another mutation with a timestamp. LWW treats all uniformly.
- **Prevents resurrection on clock skew.** If device A deletes at `updatedAt=100` and device B edits at `updatedAt=200`, the edit should win (user expectation). Pure "tombstone wins" would resurrect the annotation despite the later edit.
- **Matches user intent.** User's last action (delete or edit) should win, regardless of which type it is.

**3. Room as Source of Truth (Including Tombstones)**

Per ADR 0025: "Local Room store is always primary and queryable."

**Implementation details:**
- Room stores all annotations including tombstones (`deleted=true` records stay in the database)
- Merging always upserts to Room (via `annotationStore.upsertAll(mergeResult)`)
- UI filters `deleted=true` when querying (`observeHighlights` / `observeBookmarks` exclude deleted)
- Per-device files also contain tombstones (for the merge algorithm to see them), but they're secondary
- **Consequence:** a deleted annotation can be resurrected if another device's later edit comes in (LWW semantics)

**Why not skip tombstones from Room:**
- **Sync state clarity.** A tombstone in Room is explicit proof that the annotation was deleted and when. Skipping it loses that history.
- **Merge correctness.** If device A deletes at T=100 and we don't store that tombstone, later merging device B's edit at T=150 might resurrect the annotation incorrectly.
- **Device-offline scenario.** Device A deletes, syncs (writes tombstone to file). Device B offline, syncs in a different context later, sees the tombstone. If Room had skipped it, merge logic would have no record of the deletion.

**4. Per-Book Debounce Strategy**

Instead of debouncing per-edit-type (separate timers for highlights vs. notes), use a single debounce timer per book:

```kotlin
private val debouncingJobs = mutableMapOf<Pair<String, String>, Job>()

fun scheduleDebounce(serverId: String, itemId: String) {
    val key = serverId to itemId
    debouncingJobs[key]?.cancel()  // restart timer
    debouncingJobs[key] = scope.launch {
        delay(DEBOUNCE_DURATION_MS)  // 1000ms
        pushPending(serverId, itemId)
    }
}
```

**Why per-book, not per-edit-type:**
- **Simpler model.** One state per book (timer active or not) vs. three (one per type).
- **Adequate for user pace.** Most editing sessions have 1-3 quick edits per book, then a gap. Single timer captures this.
- **File efficiency.** Single push per debounce writes all pending annotations for the book at once, reducing I/O.

**When to use per-edit-type debounce:** later, if analytics show a specific pattern (e.g., users spend 30s rapidly recoloring, then move to the next book) where grouping color changes separately from note edits would save significant storage. Not needed for v1.

**5. Reader Lifecycle Integration**

Three touch points: open, edit, close/pause.

**On Open:**
```
EpubReaderScreen composition
  → LaunchedEffect: controller.syncOnOpen(serverId, itemId)
  → reads all device files → merges → upserts Room
  → UI observes Room, renders all non-deleted annotations
```

**On Edit (highlight/recolor/note/bookmark/delete/rename):**
```
AnnotationStore mutation (e.g., createHighlight, recolor, delete)
  → Room updated immediately
  → UI reflects change immediately
  → (separately) controller.scheduleDebounce(serverId, itemId)
  → debounce timer starts or restarts
```

**On Close/Pause:**
```
EpubReaderScreen disposed
  → DisposableEffect.onDispose(): controller.syncOnClose(serverId, itemId)
  → cancels pending debounce
  → pushes pending edits to own device file
```

**Why this order (Room → debounce, not debounce → Room):**
- **Snappy UI.** Mutations to Room are instant; user sees changes immediately.
- **Debounce is async.** Background timer doesn't block the reader.
- **File consistency.** Final file state matches Room on close.

**6. Ebook-Only Scope**

Annotations and sync are **ebook (ABS EPUB) only**. Not for:
- Audiobooks (use ABS native bookmark API)
- Readaloud (Storyteller files, not ABS EPUB)
- PDFs (different coordinate system; deferred)

**Implementation:** Controller is instantiated only when opening an ebook reader. No-op if not applicable (graceful degradation).

## Considered Options

### Option A: Merge-on-Read (Direct Sync)
Read files and merge every time; no extracted merge service.

**Pros:**
- Fewer layers

**Cons:**
- Merge logic scattered across lifecycle (open, edit, close)
- Hard to test merge in isolation
- Merge happens multiple times (open + close both re-merge)
- Idempotence not obviously guaranteed

**Rejected:** Option B (dedicated merge service) is clearer.

### Option B: Dedicated Merge Service (Chosen)
Extract merge into standalone `AnnotationMergeService`, reusable across targets.

**Pros:**
- Pure, fully testable logic
- Reusable (WebDAV, ABS native all use the same merge)
- Idempotence provable
- Easier to debug

**Cons:**
- One more layer

**Chosen:** Justified by testability and reusability.

### Option C: Tombstone Always Wins
If an annotation is marked deleted, keep it deleted regardless of later edits.

**Pros:**
- Simpler mental model

**Cons:**
- Resurrects on clock skew (device A deletes at T=100, device B edits at T=200, user sees annotation back)
- Inconsistent: other fields use LWW, only delete doesn't
- Loses user's latest intent (they edited it after deletion on their device, but it comes back)

**Rejected:** Option B (tombstone participates in LWW) is more consistent.

### Option D: Skip Tombstones from Room
Merge sees tombstones, but don't upsert them to Room (skip deleted annotations).

**Pros:**
- Smaller Room schema

**Cons:**
- No record of deletion history
- Sync state less clear
- Merge correctness issues (offline device scenarios)
- Mismatch: per-device files keep tombstones, Room doesn't

**Rejected:** Option (keep tombstones in Room) is clearer and safer.

### Option E: Per-Edit-Type Debounce
Highlights, notes, bookmarks each get their own debounce timer.

**Pros:**
- Fine-grained control (group color edits, group note edits separately)

**Cons:**
- More complex state management (three timers per book)
- Not needed for v1 (user pace is fine with single timer)

**Rejected for v1:** Option (single per-book debounce) is simpler; add per-edit-type later if data shows it's needed.

### Option F: Debounce Per-Device instead of Per-Book
One global debounce timer for all books on the device.

**Pros:**
- Single state

**Cons:**
- Editing one book restarts debounce for all books
- User closes book A, then book B pushes before expected
- Poor locality (unrelated books interfere)

**Rejected:** Option (per-book) is more intuitive.

## Consequences

- **Local-directory is temporary.** WebDAV replaces it in issue #76 without touching merge or controller; per-device files stay the same.
- **Merge is frozen logic.** All targets use the same algorithm; format and merge are durable even as transports change.
- **Room always reflects sync state.** Clients query Room for annotations; no separate "sync cache" layer.
- **Idempotence is testable.** Merge(A) = Merge(Merge(A)); re-opening a book doesn't lose or duplicate annotations.
- **Offline-first.** Edits are local immediately; debounce is best-effort; close is guaranteed to push. No network waits block the reader.
- **Tombstones visible to merge.** Device A's tombstone propagates to other devices via the merge algorithm, ensuring consistent deletion.

## Testing

**Unit tests (pure logic, no I/O):**
- `AnnotationMergeServiceTest`: LWW, tombstone handling, idempotence, time collisions, edge cases
- `AnnotationW3CCodecTest`: serialization round-trip

**Integration tests (local-directory scaffold):**
- `AnnotationSyncControllerIntegrationTest`: full sync pipeline (open → merge → Room)
- `LocalDirectoryTargetTest`: file I/O (write/read/list/corrupt-file-handling)

**Instrumented tests (reader integration):**
- `EpubReaderAnnotationSyncIntegrationTest`: lifecycle (open/edit/close triggers correct methods)

## Migration Path

1. **Issue #75:** Local-directory scaffold, core merge/controller/codec
2. **Issue #76:** WebDAV `AnnotationSyncTarget` + global sync config (URL/user/pass) + Test Connection
3. **Issue #78:** Sync UI (status, offline-resilience indicator)
4. **Future:** ABS native target, per-library sweep, payload encryption, conflict UI

Each step plugs into the `AnnotationSyncTarget` interface without breaking prior layers.

## References

- [ADR 0025 — Annotation sync: pluggable target, W3C format, per-device-file merge](0025-annotation-sync-pluggable-target-w3c-format.md)
- [W3C Web Annotation Data Model](https://www.w3.org/TR/annotation-model/)
- [Issue #75 — feat(annotation-sync): per-device-file merge engine + sync lifecycle](https://github.com/plamen-kmetski/riffle/issues/75)

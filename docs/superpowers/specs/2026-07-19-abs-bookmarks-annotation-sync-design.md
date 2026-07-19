# ABS Annotation Sync via Bookmarks — Design

**Date:** 2026-07-19
**Branch:** `pkmetski/abs-bookmark-annotations-sync`
**Author:** pkmetski + assistant

## Goal

Zero-config annotation sync for ABS users. Piggyback Riffle's per-book W3C JSON-LD annotation payload on ABS's `/api/me/item/{itemId}/bookmark` surface so ABS-only users get cross-device annotation sync without standing up a separate WebDAV server. WebDAV remains the transport for Komga (and legacy) and eventually retires from ABS books.

## Motivation

- Most Riffle users have ABS already; WebDAV is a separate server they have to stand up and secure. Setup friction is the top blocker for annotation sync.
- ABS bookmarks are stored per-user in a JSON column and are un-gated by media type (verified in ABS server source, see §Empirical probes). Titles have no server-side length cap; `time` is signed and accepts negative values.
- yaabsa (Flutter ABS client) already does this at `time = -1` with a single-bookmark JSON title. Their scheme is latest-wins and has no chunking, per-device sharding, or merge; it would be a regression for Riffle's existing multi-device model.

## Non-goals

- Komga annotation sync via anything other than WebDAV. Komga's REST exposes only `read-progress` (int page + bool completed); no free-form field.
- User-facing multi-target configuration. The composite is internal; Settings surfaces "Annotations Sync" as a single conceptual thing.
- yaabsa import. Riffle uses a disjoint sentinel range and ignores yaabsa's `time = -1`. A one-way import can be added later if requested.
- Intra-book payload compression beyond the per-chunk encoding.

## Data measurements that shaped this design

PROPFIND on the user's live WebDAV target on 2026-07-19: 22 annotation files across 15 books, 3 devices.

| pct | size |
|---|---|
| p50 | 5.9 KB |
| p75 | 56 KB |
| p90 | 66 KB |
| p95 | 964 KB |
| max | 1.05 MB |

The heaviest book carries ~1.05 MB in a single device shard (a heavily-figure-annotated book with base64 `imageBytes`), ~2.08 MB across three devices combined. This makes chunking non-optional in v1: HTTP body caps (Express default 100 KB, nginx default 1 MB) will refuse or truncate the largest payloads.

## Architecture

### The transport: `AbsBookmarkAnnotationSyncTarget`

New class in `core/data` implementing the existing `AnnotationSyncTarget` port (`core/domain/…/AnnotationSyncTarget.kt`). It talks to ABS via the existing `AbsBookmarkApi` and `AbsApiClient` in `core/network`, adapted to whatever new payload-envelope operations we need.

**Namespace:** `abs_<host>_<absUserId>`. Adds `<host>` over today's `abs_<absUserId>` to address the multi-ABS-server collision case where two different ABS servers happen to hand out the same internal user id. The migration worker handles the namespace rename on the WebDAV side too, and the merge orchestrator reads the legacy `abs_<userId>` namespace during a transition period so nothing is stranded.

**Read/write unit:** per-device sharding preserved from the WebDAV model. Each device writes its own shard; the merge orchestrator unions shards at read time via W3C annotation `id`. This keeps `AnnotationSyncTarget.enumerateDevices` / `enumerateNamespaces` / `forgetNamespace` semantics intact, and sidesteps concurrent-write races without any server-side locking.

### Layout: negative-time reserved range + chunking

Each device writes N chunks per book, at `time` values in a Riffle-reserved negative range. Titles carry `base64(gzip(w3c_jsonld_slice))` prefixed with a magic header.

**Time layout:**

```
time = -1_000_000_000 - deviceIdx * 1024 - chunkIdx

  deviceIdx  ∈ [0, 10^6)   stable-hash of device UUID into range
  chunkIdx   ∈ [0, 1024)   0 is manifest, 1..N are payload chunks
```

Provides 10⁶ devices × 1024 chunks per device. Real audio timestamps live at `time ≥ 0`; yaabsa lives at `time = -1`. No collision.

**Title format (v1):**

```
riffle:v1:<deviceShort>:<chunkIdx>:<contentHashPrefix8>:<base64gzip payload>
```

- `deviceShort` — first 8 chars of device UUID, human-debuggable
- `chunkIdx` — 0 for manifest, 1..N for payload
- `contentHashPrefix8` — first 8 hex chars of SHA-256 over the raw chunk bytes; disambiguates identical `time` slots after edits and lets the merge orchestrator detect torn writes
- `base64gzip payload` — for chunk 0 (manifest): JSON `{"v":1,"chunks":N,"encoding":"gzip+b64","fullHash":"<sha256hex>","createdAt":<epoch>,"updatedAt":<epoch>}`. For chunks 1..N: gzipped W3C JSON-LD slice.

**Chunk cap:** 48 KB per title. Conservative under Express's default 100 KB and gives headroom for base64/gzip framing. Confirmed against test ABS server via §Empirical probes.

**Read protocol:**

1. `GET /api/me` returns the user's full bookmarks list.
2. Filter to reserved range (`time ≤ -1_000_000_000`) AND title starts with `riffle:v1:`.
3. Group by `deviceShort` (from title) → per-device chunk set.
4. For each device: locate manifest (chunkIdx=0), verify chunk count matches, verify `fullHash` matches concat of chunk hashes. If not: discard that shard (torn write), log, do not surface partial data.
5. Decode surviving shards; hand off to `AnnotationMergeOrchestrator` for W3C-id-level union with local DB and any other targets.

**Write protocol:**

1. Compute the current chunk set for this device (post-merge with in-memory).
2. Compare chunk-by-chunk against the last-written state (kept in local metadata, see §Local metadata).
3. For each changed chunk: PATCH bookmark at `(itemId, time)`. If PATCH indicates no such bookmark: POST. If POST 409s: PATCH.
4. If manifest N is now smaller (annotations deleted, fewer chunks needed): DELETE the trailing bookmarks at chunks N+1..oldN.
5. Rewrite manifest (chunk 0) last, so a torn write leaves the old manifest referring to still-valid chunks and readers reject the shard cleanly.

**Conflict handling on write:**

- Empty slot → POST.
- Slot has our own prefix (`riffle:v1:<ourDeviceShort>:…`) → PATCH.
- Slot has a *foreign* prefix (unknown, or another `deviceShort`) → log warning, skip write, surface a "sync conflict" state in Settings. Do NOT overwrite. Retry on next flush.
- GC sweep only deletes titles matching our own prefix. If we didn't write it, we don't delete it.

### Composite target

New `CompositeAnnotationSyncTarget(webdav: AnnotationSyncTarget?, absBookmarks: AnnotationSyncTarget?)` in `core/data`. `AnnotationSyncTargetHolder` becomes capable of holding this composite instead of a single target.

- **Writes** fan out to every configured child. Per-target failure isolation: one target's IOException doesn't cancel the other; the dirty ledger retries the failed side.
- **Reads** union all children and are handed to the merge orchestrator, which already dedups by W3C `id`.
- **Per-book routing:** ABS-namespaced books use both children during the transition period; Komga-namespaced books only touch WebDAV. Routing is derived from the namespace (see `SyncNamespace.kt`).

### Local metadata

The last-written manifest per (namespace, itemId) is cached locally so writes can compute diffs cheaply and skip untouched chunks. Stored in a small Room table `AbsBookmarkShardStateEntity(namespace, itemId, deviceShort, chunkIdx, contentHash, updatedAt)`. Also used by the GC sweep to reap our own stale bookmarks.

### Migration

**One-shot at first launch after upgrade**, guarded by `DataStore` flag `abs_bookmark_migration_completed`.

- Background worker (variant of the existing `AnnotationSyncWorker`).
- For each ABS-scoped namespace: enumerate every book that has a WebDAV annotation file; read via WebDAV; write to ABS bookmarks via the new target.
- Idempotent — the flag flips only on full success. Any book that fails mid-migration is retried on the next launch.
- Namespace rename (`abs_<userId>` → `abs_<host>_<userId>`) happens in the same pass; the legacy WebDAV files are renamed. Merge orchestrator reads both names during the transition period so non-upgraded devices still see updates.

### Transition and retirement

- **v_X (this release):** feature ships. Dual-write to WebDAV + ABS bookmarks for ABS-namespaced books. Reads union both.
- **v_Y (3–6 months later):** client version drops WebDAV writes for ABS namespaces. WebDAV reads stay one more version so slow-upgrading devices aren't stranded.
- **v_Z (next after that):** WebDAV fully skipped for ABS namespaces. WebDAV remains active for Komga.

## Empirical probes

1. **Negative `time` round-trip.** **PASSED (2026-07-19)** against http://media-server:13378. POST/PATCH/DELETE with `time = -1_500_000_000` round-trip cleanly; `time` is honored as-is; DELETE via `/bookmark/-1500000000` returns `HTTP 200 "OK"`. Side finding: the test account already carries two yaabsa-style `time = -1` bookmarks with JSON annotation payloads — confirms real-world existence of yaabsa's scheme and validates the disjoint-range requirement.
2. **Practical title-size cap.** **PASSED (2026-07-19)** against the same server. Growing titles round-tripped losslessly at 8 KB, 16 KB, 32 KB, 48 KB, 96 KB, 128 KB, 256 KB, 512 KB, 768 KB, 1 MB, 1.5 MB, **2 MB — all HTTP 200 with byte-exact read-back**. No practical server-side cap on this deployment. v1 still uses a conservative 48 KB chunk cap because third-party users may have a reverse proxy with tighter defaults (`client_max_body_size 1m` on nginx being the most common).
3. **Viewer-role fallback.** Deferred — requires provisioning a viewer-only ABS user. Implementation defaults to graceful fallback: on any 4xx from the ABS-bookmark POST/PATCH (specifically 401/403), the composite target logs, marks the namespace read-only for this target, and surfaces "read-only ABS account — falling back to WebDAV" in Settings. To verify before v1 GA.

## Testing

- Unit: chunk encoding round-trip; time-layout math; manifest validation (accepts good, rejects torn); title-prefix filter; write-side foreign-prefix skip; GC sweep only deletes own-prefix titles.
- Unit: `AbsBookmarkAnnotationSyncTarget` against a fake `AbsBookmarkApi` — POST/PATCH/DELETE, chunk-diff writes, empty-book path, single-chunk path, multi-chunk path, torn-write recovery.
- Unit: composite target — dual-write with one child failing, dual-write with both succeeding, union read with overlapping annotations, per-book routing by namespace.
- Instrumentation (`make harness-test`): migration worker end-to-end against a fake ABS + real WebDAV target. Two-device simulated: dev A writes annotations, dev B reads and sees them.
- Empirical: the three probes above.

## Flagged issues / open items

1. **Namespace rename semantics.** Rewriting `abs_<userId>__…` files to `abs_<host>_<userId>__…` on WebDAV during migration means older devices that haven't upgraded stop seeing updates from the migrating device (they're looking at the old filenames). Mitigation: keep writing to *both* legacy and host-scoped filenames on WebDAV during the transition; drop legacy writes at v_Y. Adds transient WebDAV bookkeeping but preserves backward compat.
2. **Chunk count telemetry.** Log chunk counts per book so we can see who's approaching the 1024-chunks-per-device ceiling. Almost certainly no one will, but if base64 images push a heavy user past it, we know before the write fails.
3. **Manifest hash drift on stateless devices.** A device that clears its data loses the local `AbsBookmarkShardStateEntity` cache. On next flush it can't skip untouched chunks; it rewrites all. Correct behaviour, just wasteful. Acceptable.
4. **yaabsa interop.** Riffle ignores `time = -1` bookmarks. A future opt-in "import annotations from yaabsa" toggle in Settings can read that slot and merge into Riffle's set, then delete. Not v1.
5. **ADR needed.** New ADR (number TBD — check the branch, memory says 0046 was reserved for emphasis/bold-italic) documenting: Source/Service placement (ABS bookmark target is a Service capability of the ABS Source per ADR 0041), the negative-time layout, the title-prefix envelope, and the WebDAV retirement plan. Written alongside the implementation.
6. **Multi-account ABS.** One Android device with two ABS accounts on the same server hashes to the same `deviceShort` but different `absUserId` → different namespaces → no collision. Verified: namespace is composite.
7. **Deleted-annotation tombstones.** The 90-day tombstone TTL (ADR 0038) and dirty-ledger retry (ADR 0036) semantics carry over unchanged — the composite target is transparent to both.

## Rollout order

1. Empirical probes → results into this doc.
2. `AbsBookmarkAnnotationSyncTarget` + chunk codec + tests.
3. `CompositeAnnotationSyncTarget` + holder wiring + tests.
4. `AbsBookmarkShardStateEntity` Room table + migration test.
5. Migration worker + DataStore flag + integration test against fake ABS.
6. Settings surface: dual status ("Syncing via ABS: OK; WebDAV: OK / not configured / conflict"), viewer-role warning.
7. ADR.
8. Verify on device against user's test ABS server (http://media-server:13378).

# ADR 0047 — Annotation Sync via ABS Bookmarks

**Status:** Accepted 2026-07-19

## Context

Riffle's cross-device annotation sync (ADR 0025 pluggable target; ADR 0035 WebDAV as first
target) requires the user to stand up a WebDAV server. Setup friction is the top blocker for
users who want annotations to travel between their phone, tablet, and desktop. Meanwhile, the
Audiobookshelf (ABS) server every ABS-Source user already trusts exposes a `bookmark` surface
per `libraryItem` (`POST/PATCH/DELETE /api/me/item/{id}/bookmark`) that is:

- **Un-gated by media type** — the server accepts bookmarks on ebook-only items just as it does
  on audiobook items (verified in the ABS server source, `MeController.js`).
- **Un-capped on `title` length** (JSON column; empirically confirmed up to 2 MB round-trip on
  the developer's ABS instance).
- **Accepting of negative `time`** values — the field is a plain signed `Int` on the wire and is
  used as the (libraryItemId, time) primary key, but is never interpreted semantically by the
  server.

yaabsa (Vito0912/yaabsa, a Flutter ABS client) already piggybacks its annotation payload on a
single bookmark at `time = -1` per book, with a plain-JSON title. Two such bookmarks exist on
the developer's account today, confirming this shape is a live convention.

Meanwhile, Komga's REST exposes only `read-progress` (int `page` + bool `completed`). There is
no free-form field to hijack; Komga cannot participate in an equivalent piggyback.

## Decision

Ship a new `AnnotationSyncTarget` implementation that stores annotation shards as ABS bookmarks
in a Riffle-reserved negative-`time` range, with a `riffle:v1:` title envelope. Compose it with
the existing WebDAV target so both transports run in parallel during a transition period; retire
WebDAV writes for ABS namespaces in a later client version.

### Wire layout

- `time = -1_000_000_000 - deviceIdx * 1024 - chunkIdx`
  - `deviceIdx ∈ [0, 10^6)` — stable SHA-256 hash of the device UUID.
  - `chunkIdx ∈ [0, 1024)` — 0 is the shard manifest; 1..N are payload chunks.
  - All slots fit within `Int` (max magnitude ~2.02 × 10⁹, well below `Int.MIN_VALUE`).
- `title = riffle:v<version>:<deviceShort>:<chunkIdx>:<contentHashPrefix8>:<base64(gzip(payload))>`
  - `deviceShort` = first 8 hex chars of `SHA-256(deviceId)` — an owner tag readable at a glance
    in the ABS admin UI.
  - `contentHashPrefix8` — first 8 hex chars of `SHA-256` over the raw stored bytes; identifies
    when a chunk's content has changed.
  - Chunk 0 payload = manifest JSON `{v, chunks, encoding, fullHash, createdAt, updatedAt,
    deviceId}` — carries the full deviceId back through the wire so peers can identify each
    other in the maintenance UI.
- **48 KB per title.** Defensive against reverse proxies that impose their own body caps
  (nginx defaults to `client_max_body_size 1m`; Express defaults to 100 KB JSON body).

### Composition

`AnnotationSyncTargetHolder` observes both `AnnotationSyncConfigStore` (WebDAV) and
`SourceRepository.observeAll()` (ABS Sources). It composes:

- WebDAV configured → one WebDAV child servicing every namespace.
- Each ABS Source with an accessible token and `absUserId` → one `AbsBookmarkAnnotationSyncTarget`,
  namespace-scoped to `abs_<absUserId>` (existing `AbsWebSourceDescriptor.ABS_NAMESPACE_PREFIX`
  scheme, unchanged).
- Zero children → holder returns null.
- One child → returned directly (no `CompositeAnnotationSyncTarget` wrapper).
- Two or more children → `CompositeAnnotationSyncTarget` fans out writes and unions reads.

Per-target failure isolation: a write failure on one child does not roll back or block other
children. The dirty ledger (ADR 0036) retries each child independently. Composite throws only
when every eligible child fails.

### Collision safety with yaabsa and future third parties

Two-layer defence:

1. **Reserved negative-time range.** Riffle chunks live at `time ≤ -1_000_000_000`. yaabsa
   uses `time = -1`. Real audio bookmarks are `≥ 0`. No overlap.
2. **Mandatory `riffle:v1:` title prefix.** Reads filter by the prefix and reject anything not
   matching. Writes never overwrite a foreign prefix in our reserved range; instead they log a
   warning and surface a "sync conflict" state (retried on next flush). GC sweeps only ever
   delete titles matching our own prefix.

### Placement per ADR 0041

`AbsBookmarkAnnotationSyncTarget` is a **Service capability of the ABS Source** (its lifecycle
is tied to ABS auth) — not a standalone Service. Instantiation is per-Source via
`AbsBookmarkAnnotationSyncTargetFactory`, consumed by the holder.

### Migration and transition

- **v_X (this ADR ships in):** feature ships. Dual-write to WebDAV + ABS bookmarks for ABS
  namespaces. Reads union both.
- **Migration is implicit.** `AnnotationSyncScheduler.sweepNow(this)` already fires on every
  cold start; `AnnotationSweep` (ADR 0036) reconciles across every configured target. The first
  sweep after upgrade enumerates every book with WebDAV annotations, unions with the empty
  ABS-bookmark side, and writes back to both — the migration happens as a side-effect of the
  existing sweep with no dedicated worker.
- **v_Y (~3–6 months later):** the client stops writing to WebDAV for ABS namespaces.
- **v_Z:** WebDAV is fully skipped for ABS namespaces. WebDAV remains available for Komga.

### Empirical probes shipped with this ADR

Run against the developer's test ABS at `http://media-server:13378`:

1. **Negative-`time` round-trip.** PASSED — POST/PATCH/DELETE with `time = -1_500_000_000`
   round-trip cleanly.
2. **Practical title-size cap.** PASSED at 8 KB, 16 KB, 32 KB, 48 KB, 96 KB, 128 KB, 256 KB,
   512 KB, 768 KB, 1 MB, 1.5 MB, **2 MB** — all HTTP 200 with byte-exact readback. No
   practical server-side cap on this deployment. 48 KB chunk cap has ~40× headroom.
3. **Viewer-role fallback.** Deferred to v1 GA — requires provisioning a viewer-only ABS user.
   Design fallback: on any 401/403 from POST/PATCH, log, mark the namespace read-only for that
   target, surface a warning in Settings.

## Consequences

**Positive**

- Zero-config annotation sync for ABS-only users. WebDAV configuration is no longer a
  prerequisite; a fresh install that authenticates to ABS immediately has cross-device
  annotations.
- Existing WebDAV users lose nothing during the transition. Dual-write plus per-target failure
  isolation means a broken ABS-side or WebDAV-side write does not corrupt the other.
- Merge orchestrator (`AnnotationMergeOrchestrator`) and dirty-ledger machinery (ADR 0036)
  carry over unchanged — the composite is transparent to both.
- yaabsa users can coexist on the same account; Riffle ignores `time = -1` bookmarks and yaabsa
  ignores Riffle's deep-negative range.

**Negative / open**

- **Peer device labels are not surfaced cross-device on ABS-bookmark-only accounts.** ABS
  bookmarks have no namespace-scoped key/value slot; the `readDeviceMeta` / `writeDeviceMeta`
  port methods are no-ops for the ABS target. Peers appear in the maintenance UI with their
  UUID but no human label. Local labels via `AndroidDeviceLabelResolver` continue to work.
- **Chunk count grows with base64 image annotations.** The developer's WebDAV state has a
  1.05 MB per-device shard for one heavily-figure-annotated book; that maps to roughly ten
  48 KB chunks per device on ABS (≈30 bookmarks for that one book across three devices). All
  live in the reserved negative range and are hidden from ABS's real bookmarks UI.
- **Namespace host-scoping deferred.** The scheme remains `abs_<absUserId>`. A user with two
  ABS servers whose admin accounts share the same internal `user.id` would collide. Migration
  to `abs_<host>_<userId>` is a separate follow-up because it entails a WebDAV filename rename
  the older un-upgraded client can't understand.
- **Migration is implicit and unbounded in time.** Users who never open a rarely-touched book
  after upgrade will not see its annotations on ABS until they do. This is safe because WebDAV
  stays configured and readable during transition; nothing is lost.
- **Viewer-role probe (#3) not yet executed.** Must land before v1 GA.

## References

- Spec: `docs/superpowers/specs/2026-07-19-abs-bookmarks-annotation-sync-design.md`
- Codec: `core/data/src/main/kotlin/com/riffle/core/data/absbookmark/AbsBookmarkChunkCodec.kt`
- Target: `core/data/src/main/kotlin/com/riffle/core/data/absbookmark/AbsBookmarkAnnotationSyncTarget.kt`
- Composite: `core/data/src/main/kotlin/com/riffle/core/data/absbookmark/CompositeAnnotationSyncTarget.kt`
- yaabsa piggyback scheme: `Vito0912/yaabsa` → `lib/screens/reader/reader_annotation_sync.dart`
- ABS server bookmark handler: `advplyr/audiobookshelf` → `server/controllers/MeController.js`

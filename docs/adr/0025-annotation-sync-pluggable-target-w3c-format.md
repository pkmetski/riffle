# ADR 0025 — Annotation sync: pluggable target, W3C Web Annotation format, per-device-file merge

**Status:** Accepted
**Extends:** [ADR 0003](0003-local-first-annotations.md) (local-first annotations) — keeps its local-first core, supersedes its "defer sync until ABS adds native support / push bookmarks to the ABS bookmark API" stance.

## Context

[Annotation]s need to roam between devices eventually, with no central merge server and no obligation to a single cloud provider. ABS cannot natively store EPUB highlights/notes: its `bookmarks` API is `{ time, title }` built for audiobook timestamps, and `mediaProgress` holds one position per book — confirmed against the live server. The durable artifact is therefore the **format**, not the transport; the transport must be swappable, including a swap to a native ABS endpoint if one ever ships.

## Decision

- **Local Room store is always primary and queryable.** Sync is an optional layer on top, reached through a single `AnnotationSyncTarget` abstraction (`list / read / write-own-file`) so the backing store swaps without touching the format or schema.
- **Wire format = W3C Web Annotation Data Model** (JSON-LD): EPUB CFI as a `FragmentSelector` plus a `TextQuoteSelector` snippet, with a `riffle:` extension namespace for the merge-critical fields the standard omits — `device`, `updatedAt`, and a `deleted` tombstone.
- **Merge model = per-device files, last-write-wins per record.** One file per device per book (`<absUserId>/<itemId>/annotations-<deviceId>.jsonld`). The annotation **UUID is identity**; **`deviceId` only names a file** (a device writes only its own file, reads everyone's, merges into Room by `(uuid, updatedAt)`). Deletes propagate as tombstones. This is what "collaborative, no server" reduces to once real-time OT is excluded; annotations are independent records, so set-union with last-write-wins is sufficient (no OT needed).
- **The path namespace is the ABS user identity, not Riffle's local server id.** `<absUserId>` is the `/api/me` `user.id` from the ABS account, persisted on `Server.absUserId` at server-add time and (for legacy rows) backfilled by `ServerRepository.ensureAbsUserId`. Riffle's local `servers.id` is a per-device random UUID — using it here would make two devices configured against the same ABS server write under disjoint paths and never discover each other's files. ABS `user.id` is stable across ABS version upgrades and across token rotation; it changes only on a clean ABS reinstall with no DB restore, in which case all server-side state (progress, libraries) is gone anyway. The cross-device namespace is therefore "this ABS account", not "this ABS server install", which is the correct scope for annotations.
- **v1 ships local-only** — no target implemented — but the Room schema carries every field the format and merge need (stable UUID, `createdAt`, `updatedAt`, `originDeviceId`/`lastModifiedByDeviceId`, `deleted`, CFI anchor, snippet+chapter fallback), and a `deviceId` is minted on first run. Enabling a target later is additive, never a migration.

## Considered options

- **Abuse the ABS bookmark API as a record-store target** (encode the annotation JSON into the bookmark `title`, synthetic `time` key). Empirically works — `time` is unvalidated, `title` stores 20 KB+, multiple records per book coexist, and ABS would act as the server-side merge authority. **Rejected:** it floods the audiobook-bookmark surface that **Riffle itself will render once it becomes an audiobook player** (self-pollution, not just other-client pollution), and rides undocumented behaviour a future ABS release could break silently. Every other writable ABS surface (playlists, collections, item metadata, file upload) is either a surface Riffle will also render or the wrong scope (admin/shared, triggers scans).
- **Single shared document** (`annotations.json` all devices write). Rejected: dumb stores resolve concurrent writes as file-level last-write-wins, silently destroying a device's edits. Making one shared document merge correctly requires an OT/CRDT server — the excluded new service. Per-device files are the serverless equivalent.
- **Custom lean JSON format.** Rejected in favour of W3C: the standard's selector model is exactly the dual CFI+snippet anchor wanted, it is portable to other tooling, and "support an existing W3C standard" is a far easier eventual ask of ABS than "support Riffle's private dialect." The standard's silence on tombstones/merge is covered by the small `riffle:` extension.
- **Cloud transports first** — SAF folder (Drive/Dropbox do **not** expose writable SAF trees; only local/Nextcloud/sync-app folders do), WebDAV (off-the-shelf, fits self-hosting), consumer-cloud OAuth (no infra, heavy client code). All deferred behind the interface; v1 is local-only by choice.

## Consequences

- Two target *kinds* are anticipated behind one interface: **blob-store** (folder of per-device files, client-side merge) and **record-store** (per-record CRUD, server-side merge — e.g. a future native ABS annotation API).
- `deviceId` churn (reinstall, new device) costs only inert orphan *files*, never orphan *entries* — identity is the UUID, and any device can supersede or tombstone any record from its own file. The only true loss case is annotations created offline and never synced before an uninstall.
- The format and schema are frozen-compatible with sync from day one, so local-only v1 cannot paint the design into a corner.

## Planned first cloud transport (sync milestone — issues #75–78)

When sync is built, the first concrete target is a **WebDAV** blob-store, hosted by the developer's **Synology NAS WebDAV Server** package and reached **over Tailscale** (the NAS joins the same tailnet the ABS server already uses; QuickConnect is deliberately *not* used — it brokers DSM/Synology-app traffic, not arbitrary WebDAV from a third-party HTTP client). These are reversible config/operational choices, not part of the irreversible format/merge core above:

- **One global Annotation Sync config** (single WebDAV URL + user + password, credentials in Keystore-encrypted storage); per-account scoping is preserved by the `<absUserId>/<itemId>/` path, not by duplicate config.
- **Per-book sync on the reader lifecycle** (open + close/pause) plus a debounced push on edit; **lazy per-book bootstrap** (no library-wide background sweep, no eager full pull) — add a sweep later only if a cross-library annotations view appears.
- **No automatic GC:** tombstones and orphaned per-device files are kept indefinitely (tiny); only a manual "forget device" action, because purging a tombstone risks resurrection by a long-offline device.
- **Privacy by construction:** Tailscale (WireGuard) encrypts transit and the data lives on the user's own NAS, so E2E payload encryption is deferred — revisit only if a third-party-cloud target (e.g. Google Drive `appDataFolder`, the strong second target for non-self-hosters) is added.

## Subsequent decisions

### Manual tombstone compaction was prototyped and removed

Issue #78 originally bundled a "Compact tombstones" maintenance action alongside "Forget device". It was implemented (strip `riffle:deleted=true` records from every annotation file in a namespace) and then withdrawn before merge, because the action could not be made durable without abandoning either the serverless model or the offline-tolerance the rest of the design depends on.

The failure mode: the compaction rewrote the WebDAV files, but every active peer's local Room still held the same tombstones. The very next `pushPending` from any device re-uploaded its full local row set (including those tombstones) to its own file. So a household-wide compaction was undone by the next push, making the action cosmetic and self-undoing.

Two recoveries were considered:

1. **Compact also drops local Room tombstones on the compacting device.** Partial fix — peers still re-upload theirs.
2. **Add a per-namespace generation counter (`<namespace>__compact-gen.json`) so peers' `syncOnOpen` observes a bump and drops their own aged tombstones, bounded by a TTL (e.g. 30 days).** This is the durable design — it works under the precondition "no abandoned device offline longer than the TTL". The remaining risk (a long-offline peer holding a live copy of an annotation that was deleted+compacted will resurrect it on rejoin) is intrinsic to LWW without coordination; every comparable system that GCs without a global lock makes the same trade.

Even with option (2), the action only stops being "self-undoing" — it does not become asterisk-free. The intrinsic resurrection risk is still real, just narrowed and bounded by a TTL choice. That is more UX honesty than the rare value of "the share is slightly smaller" justifies, given that "Forget device" already covers the only common cleanup case (purge a peer's entire contribution when it's gone for good). The marginal cost of stale tombstones is a few hundred bytes per book, indefinitely; it does not need a feature.

**Decision:** ship without a tombstone-compaction action. Revisit only if real-world annotation-file sizes turn out to be a problem (orders of magnitude above current expectations), in which case the option-(2) design above is the path to implement.

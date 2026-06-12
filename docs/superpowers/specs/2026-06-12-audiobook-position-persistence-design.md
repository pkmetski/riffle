# Persist audiobook playback position locally (mirror of the ebook store)

**Date:** 2026-06-12
**Status:** Approved (design)

## Problem

The audiobook player keeps its playback position only in memory and on the
Audiobookshelf (ABS) server. On open it resumes from ABS's `currentTime`
(`AudiobookPlayerViewModel.kt:167`, `reconciledResumeSec` seeded at `:175` from
`AudiobookSession.serverCurrentTimeSec`). There is **no local audiobook-seconds
store** — the player's `PositionSaveCoordinator` is wired but its `savePosition`
callback is intentionally absent (`AudiobookPlayerViewModel.kt:100-104`, "the audiobook
resumes from ABS, so there is no local position to store").

This is asymmetric with the **ebook reader**, whose position is backed by the durable
`reading_positions` Room table (`ReadingPositionStore`) and which therefore:

- resumes from a local position when the local row is newer than the server, and
- participates as a **full, durable peer** in the matched-book reconciliation cycle —
  it seeds `localUpdatedAt` from the store (`EpubReaderViewModel.kt:575`) and is written
  back after the cycle (`:603-605`, `:659-665`), so it can *win* and survives process
  death.

Two concrete consequences of the missing audiobook store:

1. **Dropped-final-flush loss.** If the final progress PATCH is dropped at teardown
   (the `ProgressFlushScope` failure mode this codebase has hit), the last few seconds
   of listening position are lost — there is no local record to recover from on reopen.
2. **Matched-book asymmetry.** In a matched ebook↔audiobook book, the ebook side is a
   durable peer in `ReaderSync`'s cycle while the audio side is only a volatile
   in-memory peer (`AudiobookPlayerViewModel.kt:91, 243, 248`: `localUpdatedAt` resets to
   `0L` on process death). A genuinely-freshest local audio position cannot survive a
   restart to win the cycle.

## Goal

Give the audiobook a durable local position store that **mirrors the ebook
implementation** — a full, durable last-update-wins peer on both the single-peer
(unmatched) and matched paths — reusing and abstracting as much of the existing
position-store machinery as Room allows.

This is the implementation of the `pkmetski/audiobook-position-persistence` branch.

## Constraints that shape the design

- **Room forbids generic `@Entity`/`@Dao`.** Each table needs a concrete entity and DAO.
  So the table layer (entity + DAO + migration) is *necessarily* mirrored per type; it
  cannot be shared. The genuinely shareable surface is the **store contract** and the
  **timestamp/stamping policy**.
- **Last-update-wins is feasible.** ABS returns a server-side `lastUpdate` (ms) on the
  media-progress record: `NetworkServerProgress.lastUpdate`
  (`core/network/.../AbsSessionApi.kt:13-20`), read via `GET /api/me/progress/{itemId}`
  (`AbsApiClient.getProgress`). The play-session open response
  (`NetworkPlaybackSession`) carries `currentTimeSec` but **not** `lastUpdate`, so the
  open path must additionally read the progress record to obtain the server timestamp.

## Design

Five layers. Layer 1 is the shared abstraction; layer 2 is the unavoidable per-type
mirror; layers 3–5 build the audiobook store on top and wire it into the player.

### 1. Shared store core (the abstraction)

Extract the contract and stamping policy currently baked into `ReadingPositionStoreImpl`
so both stores share it:

- **`PositionStore<P>`** (core/domain) — generic over payload `P`:
  ```kotlin
  interface PositionStore<P> {
      suspend fun save(serverId: String, itemId: String, payload: P)
      suspend fun load(serverId: String, itemId: String): P?
      suspend fun loadLocalUpdatedAt(serverId: String, itemId: String): Long
      suspend fun updateLocalTimestamp(serverId: String, itemId: String, millis: Long)
  }
  ```
- **`TimestampedPositionStore<P>`** (core/data) — abstract base implementing the shared
  policy: `save` stamps `System.currentTimeMillis()`; `loadLocalUpdatedAt` returns the
  stored timestamp `?: 0L`. It delegates the per-type storage to four template methods:
  ```kotlin
  protected abstract suspend fun writePayload(serverId, itemId, payload: P, updatedAt: Long)
  protected abstract suspend fun readPayload(serverId, itemId): P?
  protected abstract suspend fun readUpdatedAt(serverId, itemId): Long?
  protected abstract suspend fun writeUpdatedAt(serverId, itemId, updatedAt: Long)
  ```
  `now()` is a `protected open` seam so tests can supply a fixed clock.
- **Refactor** `ReadingPositionStore` to `: PositionStore<String>` and
  `ReadingPositionStoreImpl` to `: TimestampedPositionStore<String>()`, implementing the
  four template methods over `ReadingPositionDao`. **Behavior is identical**; the existing
  `ReadingPositionStoreTest` is the regression guard.

Room's per-type constraint means the base is deliberately thin (the contract + the
stamping/default policy). That is the honest extent of what is shareable; the table
layer below is mirrored, not shared.

### 2. Audiobook table (per-type mirror)

Modeled exactly on `reading_positions` = payload + timestamp.

`audiobook_positions`:

| column           | type    | notes                                              |
|------------------|---------|----------------------------------------------------|
| `serverId`       | TEXT    | PK part 1; FK → `servers(id)` ON DELETE CASCADE    |
| `itemId`         | TEXT    | PK part 2                                           |
| `positionSec`    | REAL    | book-absolute listen position, seconds             |
| `localUpdatedAt` | INTEGER | `System.currentTimeMillis()` at write              |

- **`AudiobookPositionEntity`** — composite PK `(serverId, itemId)`, FK cascade, index on
  `serverId` (mirrors `ReadingPositionEntity` / `AudioPlaybackPreferencesEntity`).
- **`AudiobookPositionDao`** — `upsert`, `getByItemId(serverId, itemId)`,
  `updateLocalTimestamp(serverId, itemId, millis)` (mirrors `ReadingPositionDao`).
- **Migration `32 → 33`** per the CLAUDE.md checklist:
  - Bump `@Database(version = 33)`; register `AudiobookPositionEntity` + the DAO getter in
    `RiffleDatabase.kt`.
  - `MIGRATION_32_33` creating the table + the `serverId` index (pattern of
    `MIGRATION_31_32`, `RiffleDatabase.kt:643-658`).
  - Build so KSP exports `core/database/schemas/.../33.json`.
  - Register `MIGRATION_32_33` in `DataModule`'s `addMigrations(...)`.
  - `MigrationTest.migration32To33()` (insert a row at v32-equivalent state, validate the
    new table/columns + data preserved) and extend `migrateFullChain`.

This is server-**synced** data (unlike `readaloud_resume_positions`), so the FK-cascade +
`(serverId, itemId)` keying matches `reading_positions`, not the device-local readaloud
table.

### 3. Audiobook store

- **`AudiobookPositionStore : PositionStore<Double>`** (core/domain) — payload is plain
  `Double` seconds, matching the ebook's "no wrapper" (raw `String` CFI) choice.
- **`AudiobookPositionStoreImpl : TimestampedPositionStore<Double>()`** (core/data) over
  `AudiobookPositionDao` — implements the four template methods.
- DI: `@Binds` the store in `DataModule`; `@Provides` the DAO in `DatabaseModule`
  (mirrors the reading-position wiring).

### 4. Reconciler (pure, kept separate)

- **`AudiobookPositionReconciler`** (core/domain) — pure last-update-wins over
  `(localSec, localUpdatedAt)` vs `(remoteSec, remoteUpdatedAt)` →
  `ResumeLocal(sec) / ResumeRemote(sec, remoteUpdatedAt) / InSync(sec)`. A near-twin of
  `StorytellerPositionReconciler`.

The two reconcilers are **kept as separate types**, not merged into one generic. The
ebook reconciler is Storyteller-locator-string-specific; the audiobook one is
seconds-specific. Forcing them into one parameterized type yields a leaky abstraction for
no real saving (the bodies are a three-way timestamp comparison either way). This is the
deliberate boundary of "abstract as much as possible" — share the *store*, not the
*reconciler*. (Decision: B, not C.)

### 5. Wiring into the player

The audiobook VM injects `AudiobookPositionStore` directly, exactly as
`EpubReaderViewModel` injects `ReadingPositionStore`.

**Write paths (durability on both matched and unmatched):**

- Make `positionSaveCoordinator.savePosition` actually persist:
  `{ pos -> audiobookPositionStore.save(serverId, itemId, pos) }`.
- Call `positionSaveCoordinator.onChanged(pos)` in the **10s follow loop** (currently only
  the cold-path `onClose` runs — `pushProgressOnStop`, `:342`). This gives hot-path
  durability against process death, mirroring the ebook's save-on-scroll. `onChanged` is
  invoked on the *genuinely-advancing* ticks only (the existing
  `pos >= reconciledResumeSec - SETTLE_EPS_SEC` settle guard), so transient pre-seek /
  buffering positions are never persisted.

**Resume / matched-cycle as a durable full peer:**

- **`AudiobookSession` gains `serverLastUpdate: Long`** (ms). `openSession`
  (`AudiobookRepositoryImpl.kt:29-63`) additionally reads the progress record's
  `lastUpdate` to populate it. For an offline/local downloaded session
  (`audiobookDownloadRepository.localSession`) `serverLastUpdate = 0L`, so a present local
  row naturally wins.
- **Unmatched (single-peer) open:** the VM loads the local row
  (`load` + `loadLocalUpdatedAt`) and runs `AudiobookPositionReconciler` against
  `(serverCurrentTimeSec, serverLastUpdate)`. `startAtSec` = the winner. On a remote win,
  `updateLocalTimestamp(serverLastUpdate)` + `save` the remote position locally (the ebook
  "pull" mirror); on a local win, the local seconds drive the resume (and the existing
  follow-loop/stop push converges ABS).
- **Matched open + cycle:** seed the cycle's `localUpdatedAt` from
  `audiobookPositionStore.loadLocalUpdatedAt(serverId, itemId)` instead of a bare
  in-memory wall-clock, and after each `runAudioLedCycle` write back
  `updateLocalTimestamp(canonicalLastUpdate)` (plus persist the seeked position on a remote
  jump). **The existing transient-guard is preserved unchanged**: pre-settle ticks still
  pass `localUpdatedAt = 0L` (`AudiobookPlayerViewModel.kt:248`,
  `attachReaderSync` `:211`) so the raw audio clock can never wrongly win. The *only*
  change is that the local peer's timestamp is now persisted — so, exactly like the ebook,
  it survives process death and can legitimately win on restart. This is additive to the
  canonical reconciliation, not a rewrite of it.

This makes the audio side a durable full peer symmetric to the ebook side
(`reading_positions`): both seed `localUpdatedAt` from their store, both are written back
after the cycle, both can win, both survive process death.

## Layers touched

- **core/database** — `AudiobookPositionEntity`, `AudiobookPositionDao`; `@Database`
  v32→33 + entity/DAO registration; `MIGRATION_32_33`; exported `33.json`;
  `MigrationTest.migration32To33()` + `migrateFullChain`.
- **core/domain** — `PositionStore<P>`; `AudiobookPositionStore`;
  `AudiobookPositionReconciler`; refactor `ReadingPositionStore` to extend
  `PositionStore<String>`; `AudiobookSession.serverLastUpdate`.
- **core/data** — `TimestampedPositionStore<P>`; `AudiobookPositionStoreImpl`; refactor
  `ReadingPositionStoreImpl` onto the base; DI in `DataModule`/`DatabaseModule`;
  `openSession` reads + returns `serverLastUpdate`.
- **app** — `AudiobookPlayerViewModel`: inject the store; wire `savePosition`; add
  `onChanged` to the follow loop; reconcile on unmatched open; seed/write-back the store in
  the matched cycle.

## Testing

- **Shared base / ebook store** — existing `ReadingPositionStoreTest` must stay green
  unchanged (proves the refactor preserved behavior).
- **`AudiobookPositionStore` round-trip** — save→load returns the seconds; overwrite
  replaces; `save` stamps `localUpdatedAt`; `loadLocalUpdatedAt` defaults to `0L`;
  per-`(serverId, itemId)` isolation (mirror `ReadingPositionStoreTest`).
- **`AudiobookPositionReconciler`** — pure-unit table: local newer → `ResumeLocal`; remote
  newer → `ResumeRemote`; ties / no local → server (`InSync` / `ResumeRemote` per the
  ebook reconciler's tie rule).
- **Migration** — `MigrationTest.migration32To33()` (new table + columns, pre-existing data
  preserved) + `migrateFullChain`. Run via the core:database androidTest suite.
- **ViewModel** — unmatched open with a newer local row resumes from local, not ABS;
  matched cycle seeds `localUpdatedAt` from the store and persists the canonical timestamp.
  Phone-form-factor harness test via `make harness-test`.
- Full unit suite via `./gradlew test` (covers pure-JVM modules) before claiming green.

## Out of scope

- Changing the canonical multi-peer reconciliation logic in `CanonicalSyncCycle` /
  `ReaderSync` (the change is additive: persist the local peer's timestamp; the winner
  math is untouched).
- Merging `AudiobookPositionReconciler` and `StorytellerPositionReconciler` into one
  generic type (deliberately kept separate — see §4).
- Storing `durationSec` in the table (available from the live `timeline` / session at
  reconcile and push time; the table stays payload + timestamp like `reading_positions`).
- Any new ABS network endpoint — reuse `getProgress` / `syncAudiobookProgress`.

## Migration version coordination

This branch owns **32 → 33** (`audiobook_positions`). Any sibling branch also bumping the
schema must renumber to **33 → 34** when it lands; the changes are independent (new table),
so sequential migrations suffice.

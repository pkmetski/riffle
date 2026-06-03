# ABS-side readaloud auto-matching + reader auto-play — design

**Date:** 2026-06-03
**Status:** Approved design, ready for implementation plan

## Goals

1. **Match ABS books to Storyteller readalouds without ever opening the Storyteller (Readaloud) library.** Today auto-matching only runs against Storyteller books that are already in the local DB, and those land there only when the user navigates to the Readaloud library. Fix: pull the Storyteller readaloud data proactively so the *existing* matcher links exact matches on an ABS library load.
2. **Start readaloud playback immediately when the reader's readaloud control is pressed** — no separate Play tap.

## Background (current behavior — unchanged by this work)

`ReadaloudMatchingService.reconcileLinks()` runs at the end of every `refreshLibraryItems(...)` (ABS and Storyteller). It reads **only the local DB** (`libraryItemDao.listMatchableByServerType(...)`) and sorts each Storyteller book into:
- **Exact** (ISBN/ASIN or exact normalized title+author) → auto-linked (`userConfirmed=false`).
- **Fuzzy** (≥0.85) → `ReadaloudCandidateEntity` "pending review".
- **No match** → nothing.

Storyteller books only enter the local DB via `refreshStorytellerReadalouds(...)`, which runs only when the user opens the Readaloud library. ABS library items refresh on **every navigation into the library** (no throttle), on reconnect, and on a 10s failed-retry loop.

## Explicitly out of scope

- **Fuzzy-match handling is unchanged.** No auto-confirm of fuzzy candidates, no new ABS-side review/confirm UI. Pending-review and manual pairing stay exactly as today (Storyteller-anchored, via the review queue / Settings).
- No change to the matching algorithm or its tiers/thresholds.

## Part 1: Proactive Storyteller readaloud sync

**Trigger:** the ABS branch of `LibraryRepositoryImpl.refreshLibraryItems(libraryId)` — *before* its existing `reconcileLinks()` call — first runs a new best-effort `syncStorytellerReadalouds()` step, so Storyteller books are present locally when the matcher runs.

**New step `syncStorytellerReadalouds()`** (in `LibraryRepositoryImpl`, or a small collaborator it owns):
1. Enumerate every configured **Storyteller** server (`serverRepository` — all servers with `serverType == STORYTELLER`).
2. For each, honor the **staleness throttle**: skip if it was synced within the last `STORYTELLER_SYNC_TTL` (**10 minutes**), tracked in an in-memory `serverId → lastSyncedAtMillis` map using the existing `TimeProvider`.
3. For a non-skipped server: ensure its **readaloud library** is known (refresh that server's library list if needed to obtain the synthetic Readalouds library id), then fetch its readaloud books via that server's URL/token (server-explicit, mirroring `refreshStorytellerReadalouds`) and upsert them into `library_items`. Record the sync timestamp.
4. **Best-effort & non-blocking:** any per-server failure (offline, auth, HTTP error) is caught and skipped — it must never fail, throw out of, or stall the ABS load. After the step (whatever synced), the existing `reconcileLinks()` runs and links exact matches against whatever Storyteller data is now local.

**Active-server safety:** this is a pure background data fetch for non-active Storyteller servers — it must not change the active server, navigation, or visible-library state. Use the server-explicit API/token pattern already established (see the readaloud bundle download work).

**Net effect:** open any ABS library on a fresh launch → Storyteller readalouds sync (≤ once per server per 10 min) → exact matches auto-link → the readaloud indicator + download button appear on matched ABS books, with no Readaloud-library visit. Fuzzy/no-match books are untouched.

## Part 2: Auto-play on the reader readaloud control

`EpubReaderViewModel.openReadaloud()` currently opens the player and starts position sync but does **not** begin playback. Change it so pressing the reader top-bar readaloud control **also starts playback** via the existing play path (`onPlayTapped()` / `ensurePreparedAndPlay`), after the player opens.

- For a **matched ABS book**, the control is only enabled once the bundle is present (existing §6 behavior), so auto-play finds the bundle and plays immediately — no Play tap.
- For a **Storyteller book**, behavior matches today's Play tap: plays if the bundle is local, otherwise the existing download prompt / offline message path runs (pressing the control is the deliberate intent).
- The early-return guard (`if (!_readaloudAvailable.value) return`) stays, so a disabled/invisible control still does nothing.

## Components & files

**Part 1:**
- `core/data/.../LibraryRepositoryImpl.kt` — add `syncStorytellerReadalouds()` (enumerate Storyteller servers, throttle, best-effort fetch+upsert) and call it in the ABS branch of `refreshLibraryItems(...)` before `reconcileLinks()`.
- Reuse the existing Storyteller list/upsert path (`storytellerApi.listReadalouds`, `libraryItemDao.replaceAllForLibrary`/upsert) and server-explicit URL/token resolution (`serverRepository.getById` + `tokenStorage`).
- A constant `STORYTELLER_SYNC_TTL = 10.minutes` and an in-memory last-sync map keyed by Storyteller serverId; timestamps via `TimeProvider`.

**Part 2:**
- `app/.../feature/reader/EpubReaderViewModel.kt` — `openReadaloud()` triggers the play path after opening.

## Error handling

- Part 1: per-server try/catch; failures logged and skipped; ABS load and `reconcileLinks()` proceed regardless. Offline → no Storyteller fetch, reconcile against existing local data.
- Part 2: unchanged play-path error handling (offline message / download prompt for Storyteller; matched-ABS only reachable when bundle present).

## Testing

- **Part 1 (unit, with fakes + fake clock/TimeProvider):**
  - Enumerates all Storyteller servers and fetches+upserts their readalouds; `reconcileLinks()` runs afterward.
  - Staleness throttle: a second ABS load within the TTL does **not** re-fetch; after TTL it does.
  - Best-effort: a Storyteller server that errors is skipped and does **not** fail the ABS refresh; reconcile still runs.
  - Exact-match auto-link happens after a proactive sync without any Storyteller-library refresh call. Fuzzy/no-match unchanged.
- **Part 2:** verify `openReadaloud()` initiates playback (e.g., reaches the play path) for the bundle-present case; existing readaloud open/sync tests still pass. (Reader VM is heavy — prefer asserting the play path is invoked; an instrumented check is acceptable if a unit assertion isn't feasible.)
- Run `./gradlew test` and `make harness-test`.

## Open items to confirm during planning

1. How to resolve a Storyteller server's **Readaloud library id** for a non-active server (whether `refreshLibraries(serverId)` must run first, and whether that path exists for a non-active server) — needed to upsert items under the right library.
2. Where the last-sync throttle map should live (repository instance field vs. a small injected holder) given `LibraryRepositoryImpl`'s lifecycle (singleton?).
3. Whether `serverRepository` exposes a synchronous/`suspend` "all servers" accessor for enumeration, or if it must be collected from `observeAll()`.

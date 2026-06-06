# Persist Readaloud resume position across book sessions

**Date:** 2026-06-05
**Status:** Approved (design)

## Problem

When the user stops Readaloud with the player's **X** (close) button and then taps
**Play** again *within the same reading session*, narration correctly resumes from the
last narrated sentence (same page) or from the top of the current page (different
page). But if the user **leaves the book and re-enters it**, that resume position is
lost — the next Play starts as a brand-new "first play from the reader's position"
instead of honoring where audio last stopped.

### Root cause

The resume state lives in `EpubReaderViewModel` instance fields:

- `closeLocator: Locator?` — the reader page when the player was last closed
- `resumeFragmentRef: String?` — the sentence narrating at that moment

(`EpubReaderViewModel.kt:1018-1019`, captured in `closeReadaloud()` at lines 909-910.)

These survive an in-session X→Play only because the ViewModel object survives. Leaving
the book destroys the ViewModel (`onCleared`, line 678); re-entering constructs a fresh
one with both fields `null`. `ReadaloudResumePlanner.plan(...)` then sees
`closeHref == null`, interprets it as "never closed this session," and returns
`FromReaderPosition` — restarting from the reader's current position rather than
resuming.

## Desired behavior

Re-entering the book should behave **identically to an in-session reopen**:

- **Same page** as where audio stopped → resume the exact last narrated sentence.
- **Different page** (e.g. audio stopped on page 1, user is now on page 8) → start
  from the **top of the current page** (page 8). The current reader page overrides the
  stale audio position.

No autoplay — resume happens on the next **Play** tap (mirrors in-session behavior).
Durable across leaving/re-entering **and** app process death.

This maps **exactly** onto the existing `ReadaloudResumePlanner` logic. The only thing
missing is that the close-state must survive ViewModel destruction. **The planner is
not changed.**

## Design

Persist the close-state to disk, keyed by book; load it when the book opens; feed it
into the unchanged planner on first Play.

### Storage — new Room table

`readaloud_resume_positions`:

| column           | type    | notes                                            |
|------------------|---------|--------------------------------------------------|
| `serverId`       | TEXT    | PK part 1; FK → `servers(id)` ON DELETE CASCADE  |
| `itemId`         | TEXT    | PK part 2                                         |
| `href`           | TEXT    | reader-locator href of the close page            |
| `progression`    | REAL    | within-chapter progression of the close page     |
| `fragmentRef`    | TEXT    | `"href#fragmentId"` of the last narrated sentence|
| `localUpdatedAt` | INTEGER | `System.currentTimeMillis()` at write            |

- **Per-device, NOT server-synced.** Readaloud position has no server counterpart, so
  unlike `reading_positions` it is never pushed to ABS/Storyteller. This is also why a
  *separate* table is used rather than adding columns to the server-synced
  `reading_positions` entity (mixing device-local data into a synced entity invites
  sync bugs).
- **Keyed by the reader's `(serverId, itemId)`** — same identity as `reading_positions`.
  `href` and `fragmentRef` are in the reader's EPUB-locator space, so the reader
  identity is the correct key even for a matched-ABS book whose *audio* bundle lives
  under a different Storyteller `(audioServerId, audioBookId)`.
- FK `CASCADE` matches `reading_positions`, so deleting a server cleans up its rows.

### Flow

1. **Write on close.** In `closeReadaloud()` — and in `onReaderClosed()` when the
   player was open — persist `{href, progression, fragmentRef}` for the current
   `(serverId, itemId)`. Overwrite any existing row (upsert). The position **persists
   indefinitely** until the next close overwrites it; it is **not** cleared when
   consumed, so any number of re-entries keep resuming correctly.
2. **Load on open.** When the book opens, load the row for `(serverId, itemId)` and
   seed `closeLocator` / `resumeFragmentRef` before the first Play can occur.
3. **First Play.** `ensurePreparedAndPlay` passes the seeded values into
   `ReadaloudResumePlanner.plan(...)` exactly as today:
   - same page → `Resume(fragmentRef)`
   - different page → `PageTop(currentHref)` (the page-8 case)
   - genuinely no saved row (never readaloud'd) → `closeHref == null` →
     `FromReaderPosition`, unchanged.
4. **Navigation.** Auto-follow already moves the reader to the narrated sentence when
   `Resume` starts, so no new navigation code is required.

### Layers touched

- **core/database**
  - New `ReadaloudResumePositionEntity` + `ReadaloudResumePositionDao`.
  - Bump `@Database version` `26 → 27`; add `MIGRATION_26_27` creating the table.
  - Export schema JSON `27.json` (KSP, via a build).
  - Register `MIGRATION_26_27` in `DataModule`/`addMigrations(...)`.
  - Add `MigrationTest.migration26To27()` + extend `migrateFullChain` (per CLAUDE.md
    migration checklist).
- **core/domain**
  - `ReadaloudResumeStore` interface (mirrors `ReadingPositionStore`): `save(...)`,
    `load(serverId, itemId): ReadaloudResumePosition?`. A small `ReadaloudResumePosition`
    domain value (`href`, `progression`, `fragmentRef`).
- **core/data**
  - `ReadaloudResumeStoreImpl` backed by the DAO; DI wiring for the new DAO + store.
- **app**
  - `EpubReaderViewModel`: write on close, load on open, seed the existing fields.
    Resolve `serverId` the same way the reading-position path does.

## Testing

- **Planner** — already unit-tested; unchanged, so existing tests stand.
- **Store round-trip** — save then load returns the same `{href, progression,
  fragmentRef}`; overwrite replaces.
- **Migration** — `MigrationTest.migration26To27()` creates the table at v26→27 and
  asserts a pre-seeded row survives and the new table is present with correct columns;
  add to `migrateFullChain`.
- **ViewModel** — a fresh ViewModel seeded from a persisted close-state produces
  `Resume` when the current page matches the close page, and `PageTop` when it differs
  (the page-8 scenario). Phone-form-factor harness test via `make harness-test`.

## Composition with `readaloud-progress-sync`

The `pkmetski/readaloud-progress-sync` branch routes readaloud progress to the matched
ABS items (ebook CFI → ebook item, audiobook `currentTime` → audiobook item) and, via
three-peer sync, restores a **canonical reading position** that now folds in audiobook
listening. The two features are orthogonal and compose at **different granularities**:

- progress-sync restores the canonical position resolved to a CFI → a **page**;
- this feature refines that to the **exact narrated sentence** only when the first Play
  lands back on that same page.

They compose through the planner's existing **"current page wins"** rule: the seeded
local close-state is compared against the *live* restored locator. If the canonical
position (e.g. audiobook listened ahead on another device) restored the reader to a
different page, `PageTop` wins and the stale local sentence is ignored — no special
casing. The audiobook clock cannot provide sentence precision regardless: the ABS
audiobook is a different recording than the Storyteller readaloud bundle, so its
`currentTime` maps to a page (via CFI), never to a Storyteller SMIL clip. No
timestamp-recency guard is added — a millisecond-level `localUpdatedAt` comparison
against the reading position would be fragile and break the base case; the page-level
tiebreaker is the robust integration point. The same-page residual (audiobook advanced
*within* the restored page since the local stop → resume rewinds a few seconds) is
accepted as negligible.

**Migration version coordination.** Both branches independently introduced a
`MIGRATION_26_27` / `@Database(version = 27)` / `27.json`. This branch (implemented and
merged first) **owns 26 → 27** (creates `readaloud_resume_positions`).
`readaloud-progress-sync` must renumber its `hasAudio` change to **27 → 28** when it
lands. The two are independent schema changes (new table vs new column), so sequential
migrations suffice; no merge of the migrations themselves.

## Out of scope

- Server-side sync of readaloud position (intentionally device-local).
- Changing the planner's same-page/different-page logic.
- Autoplay on re-entry.
- A timestamp-recency guard against the canonical position (see Composition above).

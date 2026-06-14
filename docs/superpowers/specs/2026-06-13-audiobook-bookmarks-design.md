# Audiobook Bookmarks — Design

**Date:** 2026-06-13
**Status:** Approved (pending implementation plan)
**Prototype:** `.context/bookmark-prototype/index.html` (variant B)

## Summary

Let a listener mark a spot in an audiobook, give it a title, and return to it later.
Bookmarks are **local-first** (Room is the source of truth) and reconcile to
Audiobookshelf's native bookmark API on the **same cadence as audiobook position
sync**, so they appear in the ABS web UI and on other clients. The player UI stays
minimal (variant B): a one-tap add icon, two labeled list affordances (Chapters and
Bookmarks), and bookmark ticks on the scrubber.

## Goals

- One-tap "save my spot" from the audiobook player.
- Editable, human-meaningful default title (chapter + offset).
- Browse / seek-to / rename / delete bookmarks for the current book.
- Bookmarks survive offline and sync to ABS automatically, silently.
- Bookmarks interoperate with ABS (visible in the ABS web UI / other clients).

## Non-goals (YAGNI)

- Bookmark **notes** / annotations beyond a title.
- **Search** within bookmarks.
- A **cross-book** "all my bookmarks" view.
- Folders / tags / colors.
- Ebook (reader) bookmarks — that is a separate annotation track (ADR 0024); this
  spec is audiobook-only.

## Data model

New Room entity `AudiobookBookmarkEntity`, mirroring the dirty-tracking shape of
`AudiobookPositionEntity` (ADR 0030):

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT (PK) | UUID generated locally on create |
| `serverId` | TEXT | FK → `servers`, `ON DELETE CASCADE` |
| `itemId` | TEXT | ABS library item id |
| `absBookmarkId` | TEXT? | ABS's identity for this bookmark once confirmed (ABS keys on `time`); null until first successful push |
| `positionSec` | REAL | book-absolute seconds |
| `title` | TEXT | user-editable label |
| `createdAt` | INTEGER | wall-clock ms |
| `localUpdatedAt` | INTEGER | bumped on create / rename / delete |
| `lastSyncedAt` | INTEGER | row is **dirty** when `localUpdatedAt > lastSyncedAt` |
| `deleted` | INTEGER | soft-delete tombstone (so a delete can be pushed to ABS, then hard-removed once confirmed) |

Index on `(serverId, itemId)`. Bookmarks are scoped per library item, like positions.

**DB migration: v34 → v35** — create `audiobook_bookmarks` table. Follow the
migration checklist in `CLAUDE.md` (bump `@Database` version, export schema JSON,
register in `DataModule`, add `MigrationTest.migration34To35()` + extend
`migrateFullChain`).

**Identity note:** ABS identifies a bookmark by its `time` (per library item), so two
bookmarks at the exact same second are not representable server-side. We treat
`(itemId, positionSec)` as the effective server key; on the rare collision the later
write wins (the title is updated rather than a second row created).

## Sync

Bookmarks reuse the **same sync trigger and frequency as audiobook position sync** —
they piggyback on the existing audiobook progress sync path (see
`2026-06-12-audiobook-position-persistence-design.md` and
`2026-06-07-readaloud-unified-progress-sync-design.md`). No separate timer or
scheduler.

ABS native endpoints (no Riffle cloud needed):

- Create: `POST /api/me/item/{itemId}/bookmark` `{ time, title }`
- Rename: `PATCH /api/me/item/{itemId}/bookmark` `{ time, title }`
- Delete: `DELETE /api/me/item/{itemId}/bookmark/{time}`
- Read (for pull/reconcile): bookmarks are included on the ABS media-progress / `me`
  payload per library item.

These land in a new `AbsBookmarkApi` interface implemented by `AbsApiClient`, parallel
to `AbsSessionApi`/`AbsPlaybackApi`.

**Reconciliation (last-write-wins, mirrors positions):**

- **Push** dirty rows (`localUpdatedAt > lastSyncedAt`): create/rename/delete on ABS,
  then `confirmPushedIfUnchanged` (set `lastSyncedAt`, store `absBookmarkId`;
  hard-delete tombstoned rows once the server delete confirms).
- **Pull** the server's bookmark set for the item: for rows not locally dirty, accept
  server state (insert new, update title, remove ones deleted server-side) via the
  `acceptServerIfUnchanged` compare-and-swap pattern.
- Conflicts resolve by `localUpdatedAt` vs the server's `lastUpdate`, last write wins.

**Silent UX:** no per-row sync badges. If there are dirty (unsynced) bookmarks **and**
the device is offline, show a single quiet "Offline — bookmarks will sync" note at the
top of the Bookmarks sheet. Otherwise the UI says nothing about sync.

## UI (variant B — clean player)

Surfaces on `AudiobookPlayerScreen`:

- **Add (one tap):** bookmark icon in the top app-bar row (right side, beside the
  persistent speed control). Tapping opens the **create dialog**.
- **Create dialog:** single editable text field pre-filled with the default title,
  plus suggestion chips; **Save** / **Cancel**. Save shows a toast with **UNDO**.
- **Two labeled list affordances** under the scrubber: **Chapters** and
  **N bookmarks**. Each opens a **dedicated bottom sheet** (no tabs).
- **Scrubber ticks:** one tick per bookmark on the progress bar, tappable to seek.

**Default title** (pre-filled, editable):

1. `<chapter title> · <offset-into-chapter>` when the chapter has a real title
   (e.g. `The Egg · 12:45`). Offset = time since the chapter started.
2. `Chapter N · MM:SS` when there is no chapter title.
3. Absolute book timestamp (`H:MM:SS`) when the book has no chapters.

Suggestion chips offer: chapter+offset, chapter only, absolute timestamp, created
date/time.

**Shared sheet component.** Chapters and Bookmarks render through **one reusable list
sheet** (e.g. `PlayerListSheet`) parameterized by content — same row layout
(leading marker · title · subtitle · trailing). Chapters' trailing is a duration and
the current chapter is highlighted with a now-playing marker; bookmarks' trailing is a
`⋮` overflow (**Rename** / **Delete**). Tapping a row seeks and closes the sheet. This
is the structural reason Chapters ships alongside Bookmarks here — they share the sheet.

**Read-along coexistence.** On read-along-enabled audiobooks the swipe-down "Read
along" hint occupies its own top strip, above the app-bar row that carries speed + the
bookmark-add icon. The two never overlap.

## Components & boundaries

- `AudiobookBookmarkEntity` + `AudiobookBookmarkDao` (core/database) — storage + dirty
  queries (`dirtyForServer`, `acceptServerIfUnchanged`, `confirmPushedIfUnchanged`,
  upsert, soft-delete), parallel to `AudiobookPositionDao`.
- `AudiobookBookmarkStore` (core/data) — domain-facing CRUD + the reconcile entry point
  invoked by the existing audiobook sync path.
- `AbsBookmarkApi` + impl on `AbsApiClient` (core/network) — ABS bookmark endpoints and
  network DTOs.
- Domain: a small `AudiobookBookmark(id, positionSec, title, createdAt)` and a default-
  title builder that takes the `AudiobookTimeline` + current position (so the
  `Chapter · offset` logic is unit-testable without UI).
- `AudiobookPlayerViewModel` — expose `bookmarks: List<AudiobookBookmark>`, the
  computed default title for the current position, and `addBookmark/rename/delete/seekTo`.
- UI: bookmark-add icon, create dialog, `PlayerListSheet` (chapters + bookmarks),
  scrubber ticks.

## Error handling

- **Create offline:** persists locally, dirty; pushed on the next sync tick. UNDO toast
  works regardless of connectivity.
- **Push failure:** row stays dirty, retried on the next sync; no user-facing error for
  a transient failure.
- **Delete:** soft-delete immediately (disappears from UI), tombstone pushed, hard-
  removed on confirm; if the push fails the tombstone persists and retries.
- **Title collision at same second:** later write updates the existing row's title
  rather than creating a duplicate (matches ABS's `time`-keyed model).

## Testing

- DAO + migration tests (v34→v35) per `CLAUDE.md`.
- Default-title builder unit tests: chapter+offset, no-title fallback, no-chapters
  fallback, offset computation at chapter boundaries.
- Reconcile unit tests: push create/rename/delete, pull insert/update/remove,
  last-write-wins conflict, offline-then-sync, tombstone confirm.
- ViewModel tests: add/rename/delete/seek, UNDO.
- A harness/UI smoke test for add → list → seek if it fits existing patterns.

## Open questions

- Does ABS return a stable per-bookmark id, or only `time`? If only `time`, drop
  `absBookmarkId` and key purely on `(itemId, positionSec)`. Confirm against the live
  server during implementation.

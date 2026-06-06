# Readaloud progress → matched-audiobook routing

**Date:** 2026-06-06
**Status:** Approved for implementation
**Related:** ADR 0019 (three-peer sync), ADR 0021 (matching), ADR 0026 (always-ABS reader)

## Problem

When a readaloud bundle is read, its progress never reaches the matched ABS **audiobook**
item in the common case where a library keeps the ebook and the audiobook as **separate
ABS library items** (e.g. a "Books" library and an "Audiobooks" library).

Confirmed on the live test server: all readaloud matches landed on audiobook-only items,
and `ThreePeerReaderSyncFactory` builds **both** the ABS-ebook and ABS-audiobook sync
remotes from a **single** matched ABS item id (`absEndpoint`), then bails entirely when a
bundle has more than one ABS link (`absLinkCount != 1`). So audiobook `currentTime` is
either written to the wrong (ebook) item's progress record, or — for split libraries —
never written at all. `applicableRemotes` already has a dormant `{ABS_EBOOK, ABS_AUDIO}`
branch for the multi-link case, but it is dead code because the factory never builds a
coordinator for it.

## Principle

The Storyteller readaloud bundle is the hub. When it is open, **sync each progress kind to
whichever ABS items are already matched to that bundle**:

- ebook progress (`ebookLocation` CFI) → a matched item that has an **ebook**;
- audiobook progress (`currentTime` seconds) → a matched item that has **audio**.

If the bundle is matched to one combined item that has both, both land on that one item. If
it is matched to a separate ebook item and audiobook item, they land on the two items
respectively. If only one kind is matched, only that one syncs. **No forced pairing** — the
matcher is unchanged; we route to whatever links exist.

## Design

### 1. Capture audio presence at library sync (new)

ABS's library-items response carries audio info (`media.numAudioFiles` / `numTracks`) that
Riffle currently discards. Capture it so an item's media type is known from stored fields —
no per-open network call.

- `AbsLibraryItemsResponse.AbsMediaDto`: parse audio track count (`numAudioFiles`, with
  `numTracks` as fallback) → expose `hasAudio: Boolean`.
- `NetworkLibraryItem`: add `hasAudio: Boolean` (default `false`).
- `AbsApiClient` items mapping: set `hasAudio` from the DTO.
- `LibraryItemEntity`: add `hasAudio: Boolean` column.
- `LibraryItem` (domain): add `hasAudio: Boolean = false`; map through in
  `LibraryRepositoryImpl` (both the upsert path and `toDomain`).
- **Room migration 26 → 27**: add `hasAudio INTEGER NOT NULL DEFAULT 0` to `library_items`.
  Follow CLAUDE.md: bump `@Database(version = 27)`, write `MIGRATION_26_27`, build to export
  `27.json`, register in `DataModule.addMigrations(...)`, add `migration26To27()` test plus
  the new migration in `migrateFullChain`.

`hasAudio` is refreshed on every library sync (same as `ebookFormat`), so existing rows get
correct values on the next sync; the migration default of `0` is a safe transient.

### 2. ThreePeerReaderSyncFactory — resolve endpoints by media type

`createIfApplicable(openedItemId)`:

1. Resolve the link for `openedItemId` → the Storyteller bundle (unchanged).
2. Gather **all** ABS links for that bundle (`findByStorytellerBook`). Remove the
   `absLinkCount != 1` bail.
3. Among the linked ABS items (looked up via `LibraryItemDao`/repository):
   - **ebook target**: a linked item with `isSupported` (has an ebook). Prefer the opened
     item when it qualifies (it is the displayed EPUB / canonical frame, ADR 0026).
   - **audio target**: a linked item with `hasAudio`.
   - A combined item satisfies both → same id for both targets.
4. Build `absEbookEndpoint` (from the ebook target) and/or `absAudioEndpoint` (from the audio
   target). Either may be null if no matched item of that kind exists.
5. Prerequisites (Storyteller EPUB bundle cached + cross-EPUB index built) still gate the
   Storyteller remote and the ebook-CFI / SMIL translation, exactly as today. The cross-EPUB
   index is keyed on the **ebook** EPUB; if there is no ebook target (audio-only match), the
   reader has no ABS EPUB to display and the book is not openable (ADR 0026) — out of scope.

### 3. ThreePeerReaderSyncCoordinator — separate ABS endpoints

Replace the single `absEndpoint: AbsSyncEndpoint?` with `absEbookEndpoint: AbsSyncEndpoint?`
and `absAudioEndpoint: AbsSyncEndpoint?`. In the `ProgressSyncStrategy` factory lambda:

- `RemoteKind.ABS_EBOOK` → built from `absEbookEndpoint`
- `RemoteKind.ABS_AUDIO` → built from `absAudioEndpoint`

A null endpoint yields no remote for that kind (already how missing endpoints are handled).

### 4. applicableRemotes / BookSyncState — media-presence routing

Replace the count-based multi-link guard. `BookSyncState` carries which ABS targets exist
(e.g. `hasAbsEbookTarget`, `hasAbsAudioTarget`) instead of `confirmedAbsLinkCount`. Rule:

- Not matched → `{ABS_EBOOK}` (unchanged single-peer).
- Matched, prerequisites cached → the set of `{ABS_EBOOK?, ABS_AUDIO?, STORYTELLER}` for the
  targets that exist. Storyteller is included (it is one bundle → one position; the old
  "distinct users sharing one Storyteller position" guard no longer applies because we now
  route ebook/audio to their own items rather than treating multi-link as ambiguous).
- Matched, prerequisites not cached → `{ABS_EBOOK}` until they land (unchanged fallback).

### Unchanged

`ReadaloudMatcher`, the review-queue flow, the reader-open path, and the `isSupported`
gating on `LibraryItemDetailScreen` are untouched.

## Testing

- **Unit (pure JVM):**
  - `applicableRemotes` for each target combination (ebook-only, audio-only, both, neither +
    prereq states).
  - Factory endpoint resolution: given a set of links + per-item `isSupported`/`hasAudio`,
    asserts the correct ebook/audio item ids are chosen (combined → same id; split → two
    ids; opened item preferred for ebook).
  - `AbsMediaDto` → `hasAudio` parsing (numAudioFiles, numTracks fallback, zero/absent).
- **Migration:** `migration26To27()` + `migrateFullChain` per CLAUDE.md.
- **Coordinator:** ABS-ebook and ABS-audio remotes built from distinct endpoints PATCH the
  distinct item ids (extend existing three-peer coordinator tests).
- **On-device sanity (emulator):** open a bundle matched to both an ebook and an audiobook
  item and confirm `currentTime` advances on the audiobook item's `/api/me/progress` while
  `ebookLocation` advances on the ebook item's. (Requires both links present; on the test
  server this needs a title matched to both — manufacture the second link via the review
  UI / manual match if auto-matching links only one.)

## Out of scope

- Audio-led canonical position (ADR 0019 §"Audio-led canonical position") — driving the
  canonical position from the audio clock when backgrounded. Separate enhancement.
- Making audio-only matches (no ABS ebook anywhere) openable — contradicts ADR 0026.
- Any matcher precision change.

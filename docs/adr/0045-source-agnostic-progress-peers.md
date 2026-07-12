# ADR 0045 — Source-agnostic Progress Peers (ebook / audio split + non-CFI dialect)

**Status:** Accepted 2026-07-13

## Context

Issue #528 asked to implement `ProgressPeerCapability` on `KomgaCatalog` so books opened
from a Komga Source can sync reading position. Before wiring Komga in, the shared progress
engine had ABS-shaped assumptions baked into it:

- `RemoteKind` enum entries were literally `ABS_EBOOK`, `ABS_AUDIO`, `ABS_BOOKMARK` — used
  as lock keys, peer IDs, and dispatch tags across `ProgressSweep`, `ReconcileLocks`, and
  `ReaderSync`.
- `BookSyncState` carried `hasAbsEbookTarget` / `hasAbsAudioTarget` booleans.
- `ProgressPeerCapability` required every peer to implement `pushAudiobookProgress` — but
  Komga has no audiobook media.
- `CfiDialect` had only `EPUB_JS` and `READIUM_NATIVE`; there was no representation for a
  page-number position.

The rename half is mechanical, but the capability split and the dialect extension are
architectural — they define what a future non-ABS peer must and must not implement.

## Decision

**Rename `RemoteKind` values to be source-agnostic:**
- `ABS_EBOOK → EBOOK_POSITION`
- `ABS_AUDIO → AUDIO_POSITION`
- `ABS_BOOKMARK → AUDIOBOOK_BOOKMARK`

**Rename `BookSyncState` fields:** `hasAbsEbookTarget → hasEbookPeer`,
`hasAbsAudioTarget → hasAudioPeer`.

**Split `ProgressPeerCapability` into two interfaces:**

- `ProgressPeerCapability` — the ebook half. `pushEbookProgress`, `pullProgress`,
  `pullAllProgress`, and `cfiDialect`. Every peer that syncs reading position implements
  this.
- `AudiobookProgressPeerCapability` — the audio half. `pushAudiobookProgress`. Only peers
  that carry audiobook media implement this (ABS today).

The engine gates the audio pass on `is AudiobookProgressPeerCapability`. Ebook-only peers
never see an audio push and never need to stub one.

**Add `CfiDialect.PAGE_NUMBER`.** The position payload is an opaque numeric string (Komga's
`page`). `CatalogEbookProgressRemote` bypasses the CFI translator for this dialect and
passes the position bytes through both ways.

**Komga implements `ProgressPeerCapability` with `cfiDialect = PAGE_NUMBER`.** Endpoints:
- `PATCH /api/v1/books/{id}/read-progress` — body `{page, completed}`.
- `GET /api/v1/books/{id}` — returns `readProgress` embedded on the book DTO.
- `GET /api/v1/books?read_status=IN_PROGRESS,READ` — paged sweep for `pullAllProgress`.

Komga does **not** implement `AudiobookProgressPeerCapability` (no audio media).

## Consequences

- ABS keeps working unchanged — it implements both interfaces, and the engine still
  dispatches to it exactly as before. Existing regression tests (feedback loop, server-
  stamp adoption, cross-device audiobook wins) continue to hold.
- Komga books opened from a Komga Source now sync reading position: page advances push to
  Komga, and a cross-device advance pulls back and moves the reader.
- Reflowable EPUB from Komga falls back gracefully: the reader's Readium Locator JSON
  can't be decoded to a page number, so `pushEbookProgress` returns null without hitting
  the network. The local position remains authoritative on-device — the same
  local-only behaviour as `LocalFiles`. A future locator↔page mapping is the natural
  follow-up.
- The reader-side peer wrappers (`EbookProgressPeer`, `AudiobookProgressPeerAdapter`) and
  their endpoints (`CatalogEbookEndpoint`, `CatalogAudioEndpoint`) are now source-neutral
  in name and typing. `CatalogAudioEndpoint` carries both a `ProgressPeerCapability` (for
  the unified `pullProgress`) and an `AudiobookProgressPeerCapability` (for the audio
  push); constructing an audio endpoint requires both.

## Out of scope

- `ReadingSessionsCapability`, `CollectionsCapability`, `BookmarksCapability` on Komga —
  separate slices.
- A generic non-CFI position envelope for `CatalogProgress`: the current envelope's
  `ebookLocation` field is already opaque (typed as `String?`), which is enough for
  `PAGE_NUMBER`. A cleaner shape would drop the ABS-derived
  `ebookProgress`/`audioCurrentTime`/`audioDuration` fields in favour of an opaque
  position blob + dialect, but that touches `pullAllProgress` on both ABS and Komga and
  is deferred until a third dialect appears.

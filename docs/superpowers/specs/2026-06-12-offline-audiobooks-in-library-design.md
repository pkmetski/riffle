# Downloaded audiobooks appear in the library when offline

**Date:** 2026-06-12
**Status:** Approved

## Problem

When the device is offline, the library shows only items that are available locally.
Downloaded **ebooks** (EPUB/PDF) correctly appear; downloaded **audiobooks** do not —
they vanish from every library surface, even though their files are on disk and the
audiobook player can already open them offline.

### Root cause

Each library ViewModel filters offline items through a private `isAvailableOffline`:

```kotlin
private fun isAvailableOffline(item: LibraryItem): Boolean = when (item.ebookFormat) {
    EbookFormat.Epub -> epubRepository.isDownloaded(..) || epubRepository.isCached(..)
    EbookFormat.Pdf  -> pdfRepository.isDownloaded(..) || pdfRepository.isCached(..)
    else -> false
}
```

The check only consults `ebookFormat`. An audiobook-only item has
`ebookFormat == Unsupported` and falls through the `else -> false` branch, so it is
filtered out offline regardless of download state. `AudiobookDownloadRepository`
already knows the item is downloaded (via its `manifest.json` marker) and the player
already reconstructs an offline `localSession()` from it — but the library never asks.

This identical method is duplicated across four ViewModels:

- `app/.../feature/library/LibraryItemsViewModel.kt`
- `app/.../feature/library/SeriesDetailViewModel.kt`
- `app/.../feature/library/CollectionDetailViewModel.kt`
- `app/.../feature/library/FilteredBooksViewModel.kt`

Each independently injects `EpubRepository` and `PdfRepository`.

## Goal

Exact parity with ebooks: **if an item is downloaded (or, for ebooks, cached), it shows
in the library when offline.** No visual change, no new badge — a downloaded audiobook
simply appears in the grid like any other offline-available item, and tapping it opens
the audiobook player offline (already supported via `localSession()`).

## Design

### 1. The rule

An item is available offline when **either**:

- its ebook is downloaded or cached (existing behaviour, unchanged), **or**
- its audiobook is downloaded — `audiobookDownloadRepository.isDownloaded(serverId, id)`.

This is purely additive. It changes nothing for ebook-only items and naturally covers:

- audiobook-only item (`ebookFormat == Unsupported`, downloaded) → now shows
- matched item where only the audiobook was downloaded → now shows
- ebook downloaded → unchanged

Audiobooks have only a downloaded state (no "cached" tier), so the audiobook side of
the check is just `isDownloaded`.

### 2. Extract a shared checker

Replace the four duplicated private methods with a single injectable checker.

- New class `LibraryItemOfflineAvailability` in `core/domain`, depending on the three
  repository interfaces (`EpubRepository`, `PdfRepository`, `AudiobookDownloadRepository`
  — all already in `core/domain`).
- Single method: `fun isAvailableOffline(item: LibraryItem): Boolean`.
- Each of the four ViewModels injects `LibraryItemOfflineAvailability`, deletes its
  private `isAvailableOffline`, and drops the now-unused `EpubRepository` /
  `PdfRepository` constructor params **only if** they are not used elsewhere in that
  ViewModel (verify per file before removing).
- Provide it via Hilt the same way the repositories are provided (constructor
  `@Inject`; no module change needed if it is a plain injectable class).

### 3. No other changes

- No DB migration.
- No `LibraryItem` model change (`hasAudio` already exists; download state is queried
  live from the repository, exactly as ebooks do).
- No UI change.
- No player change — `localSession()` already opens downloaded audiobooks offline.

## Testing

Unit test `LibraryItemOfflineAvailability` (pure logic over fake/mock repositories):

- ebook downloaded → true
- ebook cached (not downloaded) → true
- audiobook-only, downloaded → true
- matched item, only audiobook downloaded → true
- matched item, only ebook downloaded → true
- nothing downloaded/cached → false

The four ViewModels keep their existing offline-filter tests (if any); behaviour for
ebooks is unchanged.

## Out of scope

- Any download badge or visual indicator.
- Audiobook "cached" tier (audiobooks are downloaded-only).
- Changes to how audiobooks are downloaded or played.

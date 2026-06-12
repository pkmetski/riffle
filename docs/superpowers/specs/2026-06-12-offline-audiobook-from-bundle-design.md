# Offline audiobook playback from a downloaded readaloud bundle

**Date:** 2026-06-12
**Status:** Design approved, pending spec review
**Relates to:** ADR 0023 (Storyteller synced bundle is the readaloud audio source), ADR 0029 (audiobook direct-ABS streaming + audio-led sync), ADR 0030 (durable offline progress reconcile)

## Problem

A downloaded readaloud bundle (a Storyteller synced EPUB zip) already contains the book's audio
segments — the same ABS source file, re-split into fixed segments. Today that audio is reachable
only by the in-reader readaloud player. The standalone **audiobook player** cannot use it: it streams
from ABS, or plays from its own separate per-item download (`manifest.json`). So with only the bundle
on disk and the device offline, the audiobook will not play, and the offline library does not even
list the item.

This is a real gap, not a bug: the bundle is treated purely as a sync/position-mapping aid, never as
an audio content source for the standalone player.

## Goal

1. The standalone audiobook player plays from a downloaded bundle when no dedicated audiobook download
   exists — online or offline.
2. The offline library lists an item when its bundle is downloaded, the same way it lists items with a
   downloaded ebook or audiobook.
3. **Isolation:** all knowledge that "the offline audio can come from a Storyteller bundle" lives
   behind one interface with a single implementation, so the feature can be removed — or the bundle
   type swapped — as a unit, without touching the audiobook stack or the library.

## Non-goals

- No change to online streaming behaviour when a dedicated audiobook download is absent and no bundle
  exists (still streams from ABS).
- No new download UI or trigger. This consumes the *existing* readaloud bundle download.
- No attempt to make chapter markers offline match ABS chapter boundaries exactly (see Degradation).
- The ebook-from-bundle offline fallback is explicitly out of scope (separate, heavier work that
  reopens ADR 0026).

## Design

### Isolation seam

Everything downstream of "where does the audio come from" already speaks one neutral language: an
`AudiobookSession` (ordered track URLs + their `AudiobookTrackSpan`s + an `AudiobookTimeline`) plus
book-absolute seconds. The `AudiobookController`, the `AudiobookTracks` seek math, the timeline,
durable local persistence, the last-write-wins reconcile, and the ADR-0030 dirty sweep are all
source-agnostic. There are already two producers of `AudiobookSession`:

- `AudiobookRepository.openSession()` — ABS direct stream (tokenised HTTP URLs).
- `AudiobookDownloadRepository.localSession()` — dedicated offline download (`file://` URLs).

We add a **third producer behind a new interface**, and confine all bundle specifics to its single
implementation:

```kotlin
// core/domain
interface BundleAudiobookSource {
    /** A playable session backed by a downloaded bundle's audio, or null if none for this item. */
    suspend fun localSession(serverId: String, itemId: String): AudiobookSession?

    /** True when a downloaded bundle can satisfy this item's audio offline. */
    suspend fun isAvailableOffline(serverId: String, itemId: String): Boolean
}
```

- **Implementation:** `StorytellerBundleAudiobookSource` (core/data) is the *only* audiobook- or
  library-side class that imports `ReadaloudLink` / `ReadaloudLinkRepository`,
  `ReadaloudAudioRepository`, or the bundle's zip/SMIL internals.
- **Removal / replacement:** delete the impl + its DI binding (or bind a no-op). The interface, both
  consumers, and all downstream code are untouched. A different future bundle format is a new impl
  behind the same interface.

### Part 1 — Playback resolution (download > bundle > stream)

In `AudiobookPlayerViewModel.init`, the session is resolved connectivity-independently:

```
session = downloadRepo.localSession(serverId, itemId)
       ?: bundleAudiobookSource.localSession(serverId, itemId)
       ?: audiobookRepository.openSession(serverId, itemId)
```

Rationale: if a download or a bundle is on disk, there is no reason to stream from the server. A
dedicated audiobook download outranks the bundle (it is the user's explicit, ABS-track-aligned copy);
the bundle outranks streaming.

`StorytellerBundleAudiobookSource.localSession(serverId, itemId)`:

1. `readaloudLinkRepository.findByAbsItem(serverId, itemId)` → `ReadaloudLink`. No link ⇒ `null`.
2. `readaloudAudioRepository.bundleFile(link.storytellerServerId, link.storytellerBookId)`. No bundle
   file ⇒ `null`.
3. Read the bundle's ordered audio files and per-file durations (reusing the existing
   `MediaOverlayReader` / `ReadaloudTrack` `fileOrder` + `fileDuration`).
4. Build `AudiobookTrackSpan`s with cumulative `startOffsetSec` (the bundle's segments are simply a
   different span set than ABS tracks; `AudiobookTracks.trackIndexAt` / `offsetInTrackSec` /
   `startPositionFor` work unchanged on any contiguous span set).
5. Build `trackUrls` as zip-entry references using the same scheme `ZipAudioDataSource` already
   resolves; set `SharedBundle.current` to the bundle file before `controller.prepare()`, mirroring
   what the readaloud player does.
6. Build the `AudiobookTimeline`: `durationSec` from the summed segment durations; chapters derived
   from the bundle's nav when present, else a single chapter spanning the book.
7. `serverCurrentTimeSec = 0.0`, `serverLastUpdate = 0` — the resume position comes from the durable
   local store via the existing reconcile (identical to the dedicated-download `localSession`).

Everything after session resolution is unchanged. Persistence, the last-write-wins resume, and the
ADR-0030 dirty sweep are inherited for free because the position is book-absolute seconds regardless
of audio origin. The only correctness obligation: the bundle path must mark its position rows dirty
exactly as the stream/download paths do, so the reconnect sweep pushes them to ABS. (It does — the
follow loop and stop flush write through the same `AudiobookPositionStore`.)

### Part 2 — Offline library filter

`LibraryItemOfflineAvailability.isAvailableOffline(item)` is the single source of truth behind the
library's offline filtering. It gains a `BundleAudiobookSource` dependency and ORs in the bundle:

```
ebookAvailable
  || audiobookDownloadRepository.isDownloaded(serverId, id)
  || bundleAudiobookSource.isAvailableOffline(serverId, id)
```

Because `findByAbsItem` is `suspend`, the predicate becomes `suspend`. The four library ViewModels
(`LibraryItemsViewModel`, `FilteredBooksViewModel`, `CollectionDetailViewModel`,
`SeriesDetailViewModel`) already filter inside `combine { … }` transforms, which are suspend lambdas,
so the change is mechanical: replace each inline `list.filter { isAvailableOffline(it) }` with a
suspend-aware filter (e.g. `buildList { for (i in list) if (isAvailableOffline(i)) add(i) }`).
Centralization is preserved — still one predicate, now async.

`StorytellerBundleAudiobookSource.isAvailableOffline` reuses steps 1–2 above (link present AND bundle
file present), without reading audio.

### Keying

The bundle is keyed by Storyteller `(storytellerServerId, storytellerBookId)`; the library item and
the audiobook player are keyed by ABS `(serverId, itemId)`. The `ReadaloudLink` is the bridge, and the
translation lives entirely inside `StorytellerBundleAudiobookSource` — neither consumer sees Storyteller
identifiers.

## Error handling & degradation

- No link, no bundle file, or an unreadable/truncated bundle ⇒ `localSession()` returns `null` and
  `isAvailableOffline()` returns `false`. The player falls through to ABS stream; the library simply
  omits the item. No crashes, no partially-built sessions.
- **Known degradation:** offline chapter markers come from the bundle nav and may not align with ABS
  chapter boundaries. Acceptable for v1; a later refinement can persist ABS chapters at last online
  open and prefer them.

## Testing

- `StorytellerBundleAudiobookSourceTest` (core/data): link → bundle resolution; span/offset
  construction from a fixture bundle; `durationSec` and chapter derivation; every null path (no link,
  no bundle file, unreadable bundle).
- `LibraryItemOfflineAvailabilityTest`: an item with no downloaded ebook/audiobook but a downloaded
  bundle is offline-available; the same item with no bundle is not; existing ebook/audiobook cases
  still pass.
- Resolution-order unit coverage in the player VM: dedicated download wins over bundle; bundle wins
  over stream; no-bundle falls through to stream.
- Harness/device: offline bundle playback verified on a real device (per the known emulator audio-HAL
  and Chrome-55 WebView caveats), plus the offline library listing the bundle-only item.

## Files touched (anticipated)

- New: `core/domain/.../BundleAudiobookSource.kt`, `core/data/.../StorytellerBundleAudiobookSource.kt`.
- `core/domain/.../LibraryItemOfflineAvailability.kt` — add dependency, OR in bundle, make `suspend`.
- The four library ViewModels — suspend-aware offline filter.
- `app/.../audiobook/AudiobookPlayerViewModel.kt` — insert the bundle source into the resolution chain.
- `app/.../reader/readaloud/AudioPlayerService.kt` — confirm/extend the zip-entry scheme routing for
  audiobook bundle items, and `SharedBundle` handling, if the audiobook path needs a distinct restore.
- DI wiring in `core/data/.../di/DataModule.kt` (bind `BundleAudiobookSource`; update the
  `LibraryItemOfflineAvailability` provider).

No database migration. No new download path. No ADR change required (this is an application of ADR
0023 + ADR 0029); an ADR is optional if we want to record the precedence rule.

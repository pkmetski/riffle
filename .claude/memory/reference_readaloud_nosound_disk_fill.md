---
name: reference-readaloud-nosound-disk-fill
description: "Readaloud no-sound has TWO distinct causes: the emulator audio-HAL stall (reboot fixes — the common one) and a truncated bundle from CrossEpubIndexBuilderService disk-fill (code-fixed). Don't confuse them."
metadata: 
  node_type: memory
  type: reference
  originSessionId: 70e80f6c-ef7b-40d9-839c-4c495a0bbaf3
---

Readaloud "no sound + no progression + only initial highlight" has **two distinct causes** — check the logs before blaming code:

**Cause 1 — emulator audio-HAL stall (the usual one; NOT a Riffle bug).** Logcat shows ExoPlayer init + `OMX.google.mp3.decoder` + `AudioTrack` created (so the bundle decodes fine), then a flood of `AudioTrackAudioOutput: Spurious audio timestamp (system clock mismatch)` with the **playback-head position frozen** at the same value. The HAL isn't consuming frames → silence, position never advances. **Fixed by `adb -s <emu> reboot`.** Confirmed 2026-06-04: The Martian was dead with frozen timestamps across every code fix below; after a reboot it played immediately, 0 spurious-timestamp lines. This is [[reference_readaloud_no_audio_headless_emulator]]. Diagnostic: `adb logcat | grep -c "Spurious audio timestamp"` (nonzero + frozen value = HAL stall).

**Cause 2 — truncated audio bundle from disk-fill (a real code bug, now fixed).** Distinguished from Cause 1 by `RIFFLE_RA` logcat errors (zip-open / missing-entry / playback error) — i.e. the bundle does NOT decode. Even on a bundle that looks fully downloaded (e.g. "601 MB").

Mechanism (confirmed 2026-06-04, all code-level, pre-existing on main):
- `CrossEpubIndexBuilderService.loadInputs` called `storytellerEpubFile`, which **downloaded the full Storyteller *synced bundle* (ebook+audio in one file, 400–600 MB per ADR 0023) and `cacheStore.save`d a retained copy** — once per Confirmed match, fanned out concurrently on a background scope, on every library refresh. The stale class comment claimed "the few-MB EPUB bundle, never the audio bundle"; ADR 0023 made that false. Caught red-handed: `cache/epubs` + `cache/epub-bundles` (the `EpubBundleFetcher` working dir) churned ~5 GB, dropping /data from 2.2 GB→463 MB free in minutes with no user action.
- Full disk → the user's own readaloud download truncates. `AudiobookBundleDownloader` then promoted a short `.part` to the final file with **no `written == total` check** (a stream that ends early without throwing was treated as complete). The small media-overlay track still parses → first highlight renders; ExoPlayer can't decode the clipped audio → silent, no progression.

Fixes shipped on `pkmetski/readaloud-reader-fixes`:
1. `CrossEpubIndexBuilderService` no longer downloads the Storyteller bundle — it builds the index **only when the bundle is already present locally** (checked first, cheaply). Safe because `ThreePeerReaderSyncFactory.createIfApplicable` already gates three-peer on that same locally-present bundle, so a pre-bundle index could never be used. Deleted `EpubBundleFetcher` + its DI provider (sole consumer).
2. `AudiobookBundleDownloader` rejects a short body (`total>0 && written<total` → NetworkError, `.part` kept for resume) instead of finalising a truncated bundle.
3. `ReadaloudController.onPlayerError` now logs (tag `RIFFLE_RA`) instead of swallowing — a truncated/corrupt bundle surfaces as a greppable error. `ZipAudioDataSource` logs zip-open / missing-entry failures (truncation signature).
4. `buildSentenceQuotes` deferred until playback is actually playing (so its whole-bundle Jsoup parse can't contend with audio-start I/O) and wrapped in try/catch.

To diagnose recurrence: `adb ... run-as com.riffle.app du -sk cache/epubs cache/epub-bundles` (should be small — only ABS publisher epubs, never 100 MB+), and `logcat | grep RIFFLE_RA` for a playback/zip error.

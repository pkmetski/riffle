# Volume keys yield to active audio playback

**Date:** 2026-06-09
**Status:** Approved (design)

## Problem

Inside the reader, the hardware volume keys are repurposed for page navigation
(when the user has that enabled). While audio is playing — today that's
Readaloud — the user can no longer adjust playback volume with the volume keys,
which is the natural expectation when sound is coming out of the device.

## Goal

While in-app audio is **actively playing**, the volume keys control the system
(media) volume as Android normally would. The instant playback pauses or stops
and the ebook is visible again, the keys revert to their existing reader
behavior (page navigation / inverted / disabled, per the user's settings).

No new user setting. This is a contextual default, not a preference fork: when
audio is playing, everyone wants the volume keys to change volume. The two
existing toggles (`volumeNavEnabled`, `invertVolumeKeys`) are genuine
preferences and are left untouched.

## Generic by design

The signal is "**in-app audio is playing**", deliberately *not* tied to
Readaloud. Readaloud is the first and currently only writer. Planned pure
audiobook playback will write the same flag with **no further changes** to the
key-handling code.

## Behavior decisions

- **Paused / stopped:** keys revert to normal reader behavior ("as long as no
  sound is playing and the ebook is visible, revert to page navigation").
- **Panel open while audio plays:** audio wins → **system volume**. If sound is
  coming out, the volume keys should change its loudness regardless of whether
  the Table-of-Contents drawer or formatting panel is open. (Today, with no
  audio, an open panel `Swallow`s the keys; that is unchanged.)

## Key-handling reference

`VolumeKeyEventHandler.handle(...)` is a pure function returning a
`VolumeKeyAction`:

- `NavigateForward` / `NavigateBackward` — turn the page, consume the key.
- `Swallow` — consume the key but do nothing (used so panel overlays don't
  blind-page-turn).
- `PassThrough` — Riffle declines the key; `MainActivity.onKeyDown` calls
  `super.onKeyDown(...)`, letting Android adjust system/media volume and show
  the OS volume slider. **"Audio playing → system volume" means returning
  `PassThrough`.**

## Changes

1. **`ReaderStateHolder`** — add `@Volatile var isAudioPlaying: Boolean = false`,
   alongside the existing `isReaderActive` / `isPanelOpen` flags (read on the
   key-event thread).

2. **`EpubReaderViewModel`** — extend the existing
   `playbackState.map { it.isPlaying }.distinctUntilChanged().collect { ... }`
   observer (around `EpubReaderViewModel.kt:412`) to also write
   `readerStateHolder.isAudioPlaying = isPlaying`. Reset to `false` in
   `onReaderClosed()` next to the existing `isReaderActive = false` /
   `isPanelOpen = false`. (Future audiobook playback writes the same flag.)

3. **`VolumeKeyEventHandler.handle(...)`** — add an `isAudioPlaying` parameter
   and one early return, placed so active audio wins over both nav and panel:

   ```kotlin
   if (!isReaderActive)   return PassThrough
   if (isAudioPlaying)    return PassThrough   // active audio → system volume
   if (!volumeNavEnabled) return PassThrough
   if (isPanelOpen)       return Swallow
   // … invert + navigate
   ```

4. **`MainActivity.onKeyDown`** — pass `readerStateHolder.isAudioPlaying` into
   `handle(...)`.

## Testing

Extend `VolumeKeyEventHandlerTest` (pure JVM, runs under `./gradlew test`):

- `isAudioPlaying = true` → `PassThrough`, regardless of `volumeNavEnabled`,
  `invertVolumeKeys`, or `isPanelOpen` (audio wins over the panel `Swallow`).
- `isAudioPlaying = false` → all existing cases unchanged (regression guard via
  the new default parameter in the test helper).

## Out of scope

- PDF reader has no audio; `isAudioPlaying` stays `false` there — no change.
- No new setting, no UI, no changes to `volumeNavEnabled` / `invertVolumeKeys`.

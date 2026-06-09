# Readaloud skip controls — design

**Date:** 2026-06-09
**Status:** Approved design, pending implementation plan

## Goal

Add skip controls to the Readaloud mini-player so a listener can move around
without leaving the bar:

- **Rewind 15 s** and **Forward 30 s** — fine-grained seeking.
- **Previous / Next chapter** — coarse jumps that move the *audio*, with the
  reading position auto-following.

The mini-player stays small; the expanded sheet is **not** changed in this work.

## Why these controls (and why they're not redundant)

The reader already has a chapter navigation rail (a 4 dp segmented progress bar)
whose segments jump chapters on tap. But during Readaloud that jump does **not**
stick: `PlayerCoordinator` derives the narrated fragment from the controller's
playback position, and `EpubReaderScreen`'s auto-follow drags the reading
position back to that fragment on the next poll. The rail moves the *view*; it
cannot move the *audio*.

So chapter navigation *during playback* must move the player, after which the
page follows for free. Dedicated `⏮ ⏭` buttons are the only control that can do
this — they are not redundant with the rail.

## Layout

Mini-player bar, left-to-right:

```
[ 1× ]  …spacer…  [ ⟲15 ] [ ⏮ ] [ ▶/⏸ ] [ ⏭ ] [ 30⟳ ]  …spacer…  [ ✕ ]
 speed             rewind  prev   play    next  forward          close
```

- Speed (`1×`) anchors the left end; close (`✕`) the right end.
- The five transport buttons sit centered and symmetric around play:
  fine skips on the outer edges, chapter skips hugging play.
- Rewind is **15 s**, forward is **30 s** (asymmetric on purpose — the common
  "back a little, forward a lot" listening pattern). The glyphs are Material's
  `Replay` + a "15" / `Forward`-style + a "30"; the shipped Compose version uses
  the real `Icons.Filled.Replay10`-family or vector drawables carrying the
  numerals, whichever renders the exact 15/30 cleanly.
- Skip controls are part of the playing transport, so they appear only when the
  bar is in its playable state — not while showing the offline message or the
  download-progress text (same gate as the existing play/pause + speed).

A localhost prototype of this layout (and the alternatives considered) lives at
`.context/readaloud-skip-prototype/index.html`.

## Behaviour

### Rewind 15 s / Forward 30 s

A skip seeks along a **continuous timeline across the queued audio files**, not
just within the current file. The controller queues one `MediaItem` per distinct
`audioSrc`; a 15/30 s skip near a file boundary therefore has to roll into the
adjacent file. Forward past the very end and rewind before the very start
**clamp** to the end / start of the readaloud.

The synced highlight and auto-page-turn follow automatically — they already
track the controller's reported `(audioSrc, positionSec)`.

### Previous / Next chapter

Chapters are derived from clip chapter hrefs (the portion of
`MediaOverlayClip.textFragmentRef` before `#`), in reading order. The current
chapter is the one containing the active clip at the current playback position.

- **Next (`⏭`):** seek to the first clip of the next chapter.
- **Previous (`⏮`):** restart the **current** chapter (seek to its first clip);
  but if playback is already **near the start** of the current chapter, jump to
  the first clip of the **previous** chapter instead. "Near the start" is a
  small fixed threshold measured from the current chapter's first clip
  (audiobook-standard; concrete value chosen in the plan, ~3 s).
- Each chapter jump seeks the audio; auto-follow then moves the reading position.

### Clamping at the ends

- At the **first** chapter, `⏮` is a no-op / disabled (after the
  restart-current-chapter behaviour — i.e. it can still restart chapter 1, but
  never goes "before" it).
- At the **last** chapter, `⏭` is a no-op / disabled.
- Forward 30 s clamps at the end of the last file; rewind 15 s clamps at 0.

## Components and changes

- **`ReadaloudController`** (the headless Media3 handle) gains:
  - `skipBy(deltaSec: Double)` — continuous-timeline relative seek across the
    queued items, clamped at both ends.
  - `skipToAdjacentChapter(direction)` — resolve current chapter from
    `(currentAudioSrc, positionSec)`, then seek to the first clip of the target
    chapter per the previous/next rules above. Uses the `track` it already holds.
  - Both reuse the existing private `seekToAudio(audioSrc, positionSec)` and the
    `audioIndex` playlist map.
- **`ReadaloudTrack`** gains the chapter-boundary helpers this needs, kept in the
  domain layer next to `activeClipAt` / `resolveStartClip`:
  - the ordered distinct chapter hrefs,
  - "chapter containing this clip / position", and
  - "first clip of chapter N". This keeps the chapter math unit-testable without
    a Media3 controller.
- **`PlayerCoordinator`** gains thin pass-throughs (`rewind()`, `forward()`,
  `previousChapter()`, `nextChapter()`) mirroring its existing `play/pause/setSpeed`.
- **`EpubReaderViewModel`** gains matching intent methods.
- **`ReadaloudMiniPlayer`** gains five callbacks (`onRewind`, `onForward`,
  `onPreviousChapter`, `onNextChapter` — play/pause already exists) and the new
  buttons, with `testTag`s for harness tests (`readaloud_rewind`,
  `readaloud_forward`, `readaloud_prev_chapter`, `readaloud_next_chapter`).
  `canPreviousChapter` / `canNextChapter` flags drive the end-of-book disabling.
- **`EpubReaderScreen`** wires the new callbacks to the view-model, alongside the
  existing `onPlayPause` / `onCycleSpeed`.

## Out of scope

- No changes to the expanded sheet (`ReadaloudExpandedSheet`).
- No tap-to-accumulate skip (each tap is a fixed 15 / 30 s).
- No configurable skip durations.
- No new persisted state.

## Testing

- **`ReadaloudTrack`** unit tests (pure JVM): chapter ordering, chapter-for-position,
  first-clip-of-chapter, and the near-start threshold boundary.
- **Controller / coordinator** seek behaviour: continuous-timeline skip crossing a
  file boundary and clamping at both ends; previous-chapter restart-vs-jump
  threshold; next/prev clamping at first/last chapter.
- **Mini-player** harness test (phone form factor): the new buttons render in the
  playable state, are absent in offline / downloading states, and invoke their
  callbacks; chapter buttons reflect the disabled state at book ends.
```

---
name: audiobook-position-double-count
description: audiobook scrubber jumping past durationSec (e.g. 27:50 displayed on a 19:56 book once playback crossed track 0) is double-counting between AbsolutePositionPlayer + AudiobookController
metadata: 
  node_type: memory
  type: reference
  originSessionId: d024e486-03ac-4ac5-9f9b-005e1834ce8f
---

The shared player service wraps ExoPlayer in `AbsolutePositionPlayer`, whose `getCurrentPosition()` override already projects per-track ms → book-absolute ms via `SharedAudiobookContext.spans`. The `MediaController` on the app side reports back that already-absolute value.

`AudiobookController.currentAbsoluteSec()` used to call `AudiobookTracks.absoluteSec(c.currentMediaItemIndex, c.currentPosition/1000, spans)` — which adds `spans[index].startOffsetSec` a second time.

Invisible while `currentMediaItemIndex == 0` (offset 0), so the bug only appears after playback crosses the first ABS audio file. Then displayed positionSec inflates by `startOffsetSec` and shoots past `durationSec`, the remaining display pins to `-0:00`, and `timeline.chapterAt(pos)` snaps to the last chapter.

Fix: `currentAbsoluteSec = c.currentPosition / 1000.0` — trust the service override. The 2-arg `seekTo(index, ms)` is intentionally NOT overridden in `AbsolutePositionPlayer`, so `AudiobookController.seekTo(absoluteSec)`'s own (index, offset) resolution still reaches ExoPlayer unchanged.

Reproduced on `6337437d-c75d-4256-bf4e-e1d919024737` (Fellowship of the Ring, 19:56:31 across 2 ABS audio files): scrub forward, watch the displayed total jump to ~27:50 the instant the playhead crosses ~9:58.

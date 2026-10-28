---
name: reference-readaloud-no-audio-headless-emulator
description: "Readaloud \"no sound + highlight loops between two sentences\" on the emulator is a transient audio-HAL stall (fixed by reboot), not a Riffle bug"
metadata: 
  node_type: memory
  type: reference
  originSessionId: ed927637-4a96-4001-b916-3c027483af31
---

If Storyteller readaloud shows the synced highlight **looping between two sentences with no sound** on the Conductor/Claude emulator (`emulator-5556`, AVD `Medium_Phone`, launched headless `-qt-hide-window`), it is an **emulator audio-HAL stall, NOT a Riffle bug**. Fix: **reboot the emulator guest** (`adb -s emulator-5556 reboot`) or restart the emulator — verified to clear it.

Verified 2026-06-02 by instrumenting `ReadaloudController.pushState` with position logging:
- **Broken state:** the emulator's AudioFlinger output thread was wedged — AudioTrack frames not advancing → playback head stuck. ExoPlayer then extrapolates `currentPosition` by wall-clock (~1×) and resnaps to the stuck head every **~3 s** (the audio HAL `Standby Time mSec: 3000`), producing a sawtooth (`posMs 5135.3→5138.0→5135.3…`). The reader's highlight faithfully follows the polled position, so it loops between the two sentences in that band, and there's no audible sound. Position-independent: identical sawtooth seeking to 0 s and to 5135 s. No `onPlayerError`/`onPositionDiscontinuity` during the loop.
- **After `adb reboot`:** AudioFlinger `Frames written` advances at real-time (e.g. 112640→246784 in 3 s), track active, **0 underruns**; `posMs` climbs **monotonically** `7206→…→37578`. Playback + highlight work correctly. → confirms the readaloud/seek/SMIL/highlight code is correct; the variable was the emulator's audio sink. Explains "worked before on the same emulator/chapter, then broke" — the HAL wedged mid-session.

This is why the symptom only shows on the headless emulator (no/fragile audio device); to truly test readaloud audio use a **physical device** or an audio-enabled emulator window.

Bundle facts (The Martian, storyteller id 1385738337074647, 315 MB): audio = 6 Stored (uncompressed) ~54 MB MP3s (seekable); SMIL is sentence-level with exactly-abutting clips. See [[reference-storyteller-server]].

Separate, real (minor) issue found same session: streamed bundle downloads (`AudiobookBundleApi.openBundleStream` / `StorytellerBundleApi.downloadBundle`) leak the OkHttp `Response`/connection when cancelled mid-stream (closing the readaloud bar / navigating away) — "A connection to …:8001 was leaked". Consumers use `body.use{}`, so it's the cancel path, not the happy path. Worth a small fix.

# Readaloud progress sync — decoupled ebook vs audiobook

**Date:** 2026-06-07 (revised after a third data-loss incident)
**Status:** Page-led baseline shipped (safe, no erase). Decoupled audiobook-follow proposed, NOT yet implemented.
**Related:** ADR 0019 (three-peer sync), ADR 0026 (always-ABS reader),
`2026-06-06-readaloud-audiobook-progress-routing-design.md`

## The lesson (why the "unified canonical" model kept erasing the book)

The earlier idea — "one logical position; whichever representation (ebook / audiobook / local) is
newest wins and propagates to all" — is wrong **whenever the audio position and the reading
position diverge**, which happens routinely:

- Readaloud playback can start **behind** the reading position. `resolveStartClip` matches the
  reader's **ABS-EPUB** href against the player's **Storyteller-EPUB** clips; when it can't, playback
  falls back to `controller.play()` = **book start**. The audio clock then runs from ~0.
- Any code path that fed the audio position into the **shared canonical** then propagated that
  start position to the **ebook** and overwrote real reading progress → "book progress erased."

This bit three times (erase → audiobook-stopped → erase). The root cause was never the
reconciliation shape; it was **letting the audio write the ebook**.

## The rule

- **The ebook location is driven ONLY by the reading position (the page).** The audio clock must
  never write the ebook, directly or via a shared canonical. This is invariant and is what makes
  the book impossible to erase from audio.
- **The audiobook `currentTime` is driven by the audio** while readaloud plays, as a **separate,
  push-only** update that never touches the ebook, the reading position, or the reader (no jump).

So the two representations are **decoupled**: page → ebook (full reconcile, can pull cross-device);
audio → audiobook (push-only while playing). They are NOT kept in lockstep through one canonical.

## Current state (shipped, safe)

`runThreePeerCycle` is **page-led**: the canonical is the genuine reading position with its stored
timestamp; it reconciles the ebook + Storyteller (+ the audiobook, page-derived) and can pull a
genuinely-newer server position on open. The audio position is not involved, so it cannot erase the
ebook. Consequence: the audiobook only advances to the **reading** position (on the 30s tick /
close), not the live audio position — i.e. listening with the page still doesn't move it.

## Proposed: decoupled audiobook-follow (needs buy-in + real-device verification)

While readaloud is playing, a separate path PATCHes **only** the ABS audiobook item's `currentTime`
from the audio position, on the existing 30s cadence + on pause/close. It:

- never writes the ebook / reading position / Storyteller, and never jumps the reader (push-only);
- is gated so it only runs once a clip is genuinely narrating (not the seek/stall window);
- worst case writes a slightly-wrong audiobook time — it can never erase the book.

Open questions to settle before coding:

1. **Audiobook seconds source.** `positionSec` is per audio file; for a multi-file audiobook the
   absolute `currentTime` = sum(prior file durations) + `positionSec`. Single-file (the common
   case, incl. the test book) is `positionSec` directly. Need the absolute offset for multi-file.
2. **Cross-device audiobook→ebook resume.** Push-only means opening Riffle won't jump the reader to
   where you listened on the ABS app. Acceptable? (Safety vs that convenience.)
3. **The Storyteller↔ABS-audiobook timeline premise** still must hold (the bundle's SMIL seconds ==
   the ABS audiobook recording).

## Verification

Real device with working audio (the emulator's audio HAL stalls and feeds the bogus `0` that caused
the erase). Reset the test-server progress records I polluted on The Martian / 2001 first.

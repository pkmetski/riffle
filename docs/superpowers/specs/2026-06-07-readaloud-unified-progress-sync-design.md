# Readaloud progress sync — decoupled ebook vs audiobook

**Date:** 2026-06-07 (revised after a third data-loss incident)
**Status:** Page-led baseline + decoupled push-only audiobook-follow implemented. Needs real-device verification.
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

## The fourth bug (ebook written to ~end-of-book) — the audiobook was still a reconciled peer

Symptom: the ebook server location was being set to ~end-of-book when reading from the beginning,
and the reader jumped there. Root cause, **reproduced deterministically in an in-memory test**
(`ThreePeerReaderSyncCoordinatorTest.an audiobook ahead of the reading position must NOT drive the
ebook…`): the "decoupled push-only audiobook" was *not* decoupled. The audiobook (`RemoteKind.ABS_AUDIO`)
was **still a reconciled inbound peer** in `applicableRemotes`. So the push wrote the audiobook
`currentTime` with a fresh timestamp, and the **very next cycle read it back**, it won inbound (fresh
timestamp), and its position was propagated to the **ebook** — the exact erase mechanism, now via a
feedback loop through the audiobook record. Any audio-ahead-of-page divergence (a prior push, or
another device) triggered it.

Fix: **`ABS_AUDIO` is no longer a `RemoteKind` at all.** The reconcile set is `{ABS_EBOOK,
STORYTELLER}`. The audiobook is written *only* by `pushAudiobookSeconds` and never read back, so it
is structurally incapable of driving the ebook.

## Current state (shipped, safe)

`runThreePeerCycle` is **page-led**: the canonical is the genuine reading position with its stored
timestamp; it reconciles **only the ebook + Storyteller** and can pull a genuinely-newer server
position on open. The audiobook is not in the reconcile set, so neither the audio clock nor the
audiobook record can ever erase the ebook. Consequence: the audiobook advances only via the
push-only follow below (while listening); reading the page alone does not move the audiobook.

## Implemented: decoupled push-only audiobook-follow

`ThreePeerReaderSyncCoordinator.pushAudiobookSeconds(seconds)` PATCHes **only** the ABS audiobook
item's `currentTime` (+ percentage from its duration). `runThreePeerCycle` calls it after the
page-led cycle, gated on `isPlaying && activeFragmentRef != null` (a sentence is genuinely
narrating), with `seconds = positionSec`. It:

- never writes the ebook / reading position / Storyteller, and never jumps the reader (push-only);
- runs on the existing open / 30s / resume / close cadence;
- worst case writes a slightly-wrong audiobook time — it can never erase the book.

Remaining caveats / TODO:

1. **Multi-file audiobooks.** `positionSec` is per audio file; the absolute `currentTime` =
   sum(prior file durations) + `positionSec`. Single-file (the common case, incl. the test book) is
   `positionSec` directly. Multi-file needs the absolute offset — TODO.
2. **No cross-device audiobook→ebook resume.** Push-only by design: opening Riffle won't jump the
   reader to where you listened on the ABS app. (Deliberate: safety over that convenience.)
3. **The Storyteller↔ABS-audiobook timeline premise** must hold (the bundle's SMIL seconds == the
   ABS audiobook recording) for the pushed seconds to be meaningful.

## Verification

Real device with working audio (the emulator's audio HAL stalls and feeds the bogus `0` that caused
the erase). Reset the test-server progress records I polluted on The Martian / 2001 first.

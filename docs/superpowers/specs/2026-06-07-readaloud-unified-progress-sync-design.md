# Readaloud progress sync — decoupled ebook vs audiobook

**Date:** 2026-06-07 (revised after a third data-loss incident)
**Status:** Page-led baseline + inbound audiobook reconcile (feedback loop closed via the push's
returned server timestamp) + push-only audiobook-follow. Verified in-memory; needs real-device check.
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

## The fourth bug (ebook written to ~end-of-book) — a feedback loop through the audiobook record

Symptom: the ebook server location was being set to ~end-of-book when reading from the beginning,
and the reader jumped there. Root cause, **reproduced deterministically in an in-memory test**: the
"decoupled push-only audiobook" was *not* decoupled. The audiobook was **still a reconciled inbound
peer**, so the push wrote the audiobook `currentTime` with a fresh server timestamp, and the **very
next cycle read it back**, it won inbound, and its position propagated to the **ebook** — the erase
mechanism, now via a feedback loop through the audiobook record.

The first cut was a sledgehammer: drop `ABS_AUDIO` from the reconcile set entirely (push-only, never
read back). That killed the erase but also killed **inbound** audiobook sync — a cross-device listen
was no longer reflected locally, which the product needs.

## Final design — audiobook reconciled INBOUND, feedback loop closed at the timestamp layer

The real fault was timestamp hygiene, not the inbound direction. So the audiobook is a reconciled
peer again, but:

- **Inbound-only:** `AbsAudiobookInboundRemote.tryGet` reads `currentTime` → bundle-translated
  canonical, so a genuinely-newer listen wins the cycle and moves the reader (and propagates to the
  ebook — "if the audiobook is later, compute the page and override it too"). `tryPatch` is a **no-op**
  — the cycle never writes the audiobook.
- **Outbound is the push:** `pushAudiobookSeconds` writes only the audiobook `currentTime` from the
  live audio clock, and **returns the server's `lastUpdate`**. The caller records that as the local
  timestamp, so the inbound remote reads our own write back as **equal** (local wins ties), never
  newer. Only a write from another client outranks the reading position. That single timestamp record
  is what closes the loop without dropping inbound sync. All push callers (30s cycle, pause, close)
  go through the one helper, so every push is covered.
- **The ebook is never driven by the raw audio clock.** It moves only when a *genuinely-newer* peer
  wins; a behind/early local audio clock can't, because its push reads back as equal-or-older.

Triggers: open, **resume** (so a position updated on the server while the app sat paused/standby is
pulled the moment the reader resumes, even with the book still open), and the 30s cycle. Active
reading keeps `localUpdatedAt` fresh (page turns save), so the local position wins and there are no
surprise mid-read jumps; an idle/standby reader lets a newer server position win.

In-memory coverage (`ThreePeerReaderSyncCoordinatorTest`, no AVD): newer-Storyteller wins; local
newest writes ebook+Storyteller but never the audiobook; **a genuinely-newer audiobook jumps the
reader to the bundle page**; **our own push does not read back as newer (feedback closed)**; push
returns the timestamp and touches only the audiobook; split-library item routing.

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

# ADR 0029 — Audiobook playback streams direct from ABS and reuses the three-peer cycle as an audio-led driver

> **Amended by [ADR 0041](0041-source-and-service-abstractions-replace-server.md)** — streaming source generalises to any **Source** implementing `AudiobookMediaCapability`; ABS remains the reference (and only shipping) implementation. Peer terminology aligns with the Progress Peer definition in ADR 0041.

**Status:** Accepted
**Relates to:** [ADR 0019](0019-three-peer-unified-canonical-progress-sync.md), [ADR 0023](0023-storyteller-synced-bundle-is-the-readaloud-audio-source.md), [ADR 0026](0026-storyteller-as-settings-only-readaloud-backend.md), [ADR 0028](0028-per-book-audio-playback-settings-keyed-by-audio-identity.md).

## Context

Riffle is becoming a full audiobook player, not only an ebook reader with Readaloud. Until now **every second of audio Riffle has ever played came from the Storyteller synced bundle** (a ZIP read by `ZipAudioDataSource`, ADR 0023); the ABS API client has progress-sync methods but **no audio-streaming path**. Consequently an ABS audiobook with no Storyteller match was an *Unsupported Library Item* — unplayable.

We want to play **any** ABS audiobook, including the common **audiobook-only** item that has no paired ebook (split libraries: a Books-library ebook item and a separate Audiobooks-library item). "Support audiobooks" therefore cannot be gated on Storyteller alignment.

Two crux questions:

1. **Where does the audio come from?** Storyteller-only (reuse the bundle pipeline, but only aligned books play) vs. **direct ABS streaming** (every ABS audiobook plays, new network + playback path).
2. **How does an audiobook's progress sync?** A bespoke single-peer audiobook sync, or **reuse the existing three-peer cycle** so an audiobook "behaves exactly like an ebook."

## Decision

**Stream audiobook audio directly from ABS, and reuse `ThreePeerSyncCycle` unchanged as the one sync engine — with the audiobook player acting as an audio-led driver of the same canonical position. The set of sync peers is intrinsic to the item, not to the surface it was opened from.**

### Audio source

Audio is streamed from the **ABS** item's own audio (an ABS play session → tracked content URLs), via the existing Media3 `AudioPlayerService`/`MediaSession`. The Storyteller bundle is **not** the audio source for the audiobook player. For a *matched* book the bundle is still consulted, but **only** for the seconds↔canonical SMIL translation (`audioSecondsToCanonical`), never for the audio bytes.

### One sync engine, peer set intrinsic to the item

- **Audiobook-only, unmatched** → single peer `{ABS_AUDIO}`. The canonical position **is** seconds; the `ABS_AUDIO` convert-to/from canonical is identity. Symmetric with an ebook-only book's single `{ABS_EBOOK}` peer.
- **Matched Readaloud** (ebook item + audiobook item linked by Storyteller) → all three peers (`ABS_EBOOK`, `ABS_AUDIO`, `STORYTELLER`), reconciled by the *same* coordinator, whether opened from the reader or the audiobook player.
- **Matched but bundle/cross-EPUB index not yet cached** → degrades to single `{ABS_AUDIO}` peer until prerequisites land, then upgrades on a later cycle — the rule `applicableRemotes` already encodes via `prerequisitesCached`.

The only thing the open surface changes is **which peer drives the canonical**: the reader is text-led (canonical = EPUB locator); the audiobook player is **audio-led** (canonical = audio seconds, converted out to the ebook CFI).

### Storyteller is no longer a sync peer — two ABS peers only

The reconciliation set is reduced from three peers to **two**: `{ABS_EBOOK, ABS_AUDIO}`. Storyteller's own position record (a Readium Locator written back to the Storyteller server via `StorytellerPositionApi`) is **dropped as a peer** — it was redundant with the two ABS records (the same logical position) and Riffle is not a Storyteller *client*. The Storyteller **bundle** stays essential, but only as the **translation map** (SMIL + cross-EPUB index) between audio seconds and the ABS EPUB's text coordinates — never written back. This removes one remote to read/write/convert/timestamp per cycle and slightly de-risks the last-update-wins race. (A separate standalone `StorytellerPositionSyncController` loop still exists for the legacy case of a book opened directly from a Storyteller server; it is not part of the canonical reconciliation cycle.)

Because the cycle is generic over its remote set and is no longer three-peer, the `ThreePeer*` types were renamed to peer-count-neutral names: `ThreePeerSyncCycle → CanonicalSyncCycle`, `ThreePeerReaderSyncCoordinator → ReaderSyncCoordinator`, `ThreePeerReaderSyncFactory → ReaderSyncFactory`, `ThreePeerCycleResult → SyncCycleResult`, `ThreePeerReaderCycleResult → ReaderSyncCycleResult`. (ADR 0019's filename keeps its historical "three-peer" slug.)

To make this drivable without an open EPUB reader, the cycle driving is **lifted out of `EpubReaderViewModel`** into a reader-independent owner; `applicableRemotes` is generalised so the unmatched base peer is "whichever of ebook/audio the item has," not a hardcoded `{ABS_EBOOK}`.

## Considered options

- **Storyteller-only audiobooks (rejected).** Would only play the handful of books a user has aligned — the wrong product for an audiobook host whose whole point is its audiobooks. The reuse it buys is the UI/MediaSession, which we get anyway; the audio source is new regardless.
- **A bespoke single-peer audiobook sync, decoupled from three-peer (rejected by the user).** Splits the hard-won last-update-wins / timestamp-hygiene / feedback-loop logic into two implementations that would drift. An audiobook must "behave exactly like an ebook," so it reuses the same engine.

## Consequences

- **This reverses a previously-documented invariant.** During the 2026-06 erase saga an intermediate design declared *"the audio position must NEVER drive the ebook / shared canonical"* (reverted commit 589ec5b). The **shipped** design already makes `ABS_AUDIO` a full bidirectional peer that drives the ebook on a genuinely-newer listen (`ApplicableRemotes.kt`), so audio-led driving is established, correct behavior — not a violation. This ADR makes that explicit and load-bearing.
- **Resume discipline is the guard.** Audio-led canonical is safe only because the player opens at the **reconciled** position (last-update-wins across peers), never at book-start. A player that started behind the reconciled position could push a stale position outward — the same failure mode as the reader's `resolveStartClip` book-start fallback. The player must resume-at-reconciled before it is allowed to drive the canonical.
- **Combined items** (one ABS item with ebook + audio) become both readable and listenable; the detail screen offers Read and Listen independently (the `isSupported` boolean splits into `hasEbook`/`hasAudio` capabilities).
- **Out of scope for v1** (each a later feature): the persistent now-playing mini-bar, chapter-list jump sheet, sleep timer, audiobook bookmarks. (**Update:** offline audiobook download — listed here originally as out of scope, "v1 is stream-only" — shipped in #136; an audiobook can now be downloaded and listened to offline, and its offline progress is durably reconciled per [ADR 0030](0030-durable-offline-progress-reconcile.md).)

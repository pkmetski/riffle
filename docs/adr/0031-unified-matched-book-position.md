# ADR 0031 — Unified matched-book position: reconcile-on-entry, flush-on-exit across ebook, audiobook, and readaloud

**Status:** Accepted
**Relates to:** [ADR 0019](0019-three-peer-unified-canonical-progress-sync.md), [ADR 0029](0029-audiobook-direct-abs-streaming-audio-led-sync.md), [ADR 0030](0030-durable-offline-progress-reconcile.md).

## Context

A matched book has **three** position representations that the user experiences as one place in the book:

- **ebook reading position** — `reading_positions` (a Readium locator) + the ABS ebook media-progress record (CFI).
- **audiobook (listening) position** — `audiobook_positions` (book-absolute seconds) + the ABS audiobook media-progress record.
- **readaloud position** — the narrated sentence (`readaloud_resume_positions`, a fragment ref), played inside the reader from the Storyteller bundle.

They are bridged: the bundle's SMIL maps a narrated fragment to audio seconds and to the ebook text. In principle, advancing any one should move all three. In practice the wiring is incomplete and asymmetric, which surfaces as "listening to readaloud doesn't move the audiobook," "listening to the audiobook doesn't move readaloud," and "readaloud doesn't move the ebook":

1. **Readaloud advances the saved ebook position only at column/page granularity.** Auto-follow snaps the column via `ColumnSnap`; the persisted reading position comes from `fragment.currentLocator`, not the sentence-precise `fragmentLocator` the app already builds for the highlight. So the exact narrated sentence — which we *have* — isn't what's saved.
2. **Readaloud → audiobook is over-gated.** The live audiobook push requires the full `ReaderSyncCoordinator` (`readerSync`), which needs the **cross-EPUB index + ABS EPUB cached + a single confirmed link**. But the fragment→seconds translation needs only the **bundle's SMIL**, which is present whenever readaloud can play. So in a common state (index not built) readaloud plays but nothing syncs to the audiobook.
3. **Audiobook → readaloud is missing.** The audiobook player never writes `readaloud_resume_positions`, so reopening the reader and starting readaloud ignores where the audiobook got to (it also prefers the stale saved sentence over the current reading position).
4. **Entry reconciles only against ABS, not the local sibling stores.** The reader's open-time cycle GETs the ABS audiobook record but never reads the local `audiobook_positions`; the audiobook player reads its local store *and* ABS. So an **offline** listen (local-only, ABS still stale) doesn't move readaloud's start until the durable sweep pushes it to ABS and a later GET sees it.
5. **Exit flushes are partial.** Readaloud close/pause pushes only the ABS audiobook record (gated); it doesn't persist the local stores or the sentence-precise ebook position. The audiobook player doesn't flush on close/pause.

## Decision

Treat the matched book as **one canonical position** with three representations, and make every **entry** reconcile it and every **exit** flush it — across the **local stores *and* ABS**, not ABS alone.

- **Reconcile on entry (last-update-wins across local + ABS).** When the reader/readaloud opens and when the audiobook player opens, the start position is the newest of: the local `reading_positions`, the local `audiobook_positions`, and the ABS ebook/audiobook records — each compared by its `lastUpdate`/`localUpdatedAt`, translated to the opening surface's frame through the bundle. The reader must consult the **local audiobook store** (not only ABS), so an offline listen moves the readaloud start; the player already consults both.
- **Flush on exit (readaloud close/pause AND audiobook close/pause).** On exit the surface persists the **full** position: `reading_positions` (sentence-precise — the narrated `fragmentLocator`, which the app already builds), `audiobook_positions` (the translated seconds), and `readaloud_resume_positions`, and pushes the ABS ebook + audiobook records. The flush runs on a survivable scope (it routinely precedes leaving the book).
- **The narrated sentence is the precise anchor.** The ebook reading position is persisted from the sentence's text-anchored `fragmentLocator`, not the column-level `currentLocator`, so the ebook reflects exactly where readaloud is.
- **Un-gate readaloud → audiobook from the full coordinator.** The fragment→seconds push needs only the bundle SMIL; it must work whenever readaloud plays, independent of whether the cross-EPUB index (needed only for the ebook-CFI translation) is built.

## Invariant

> For a matched book, the newest of {local ebook, local audiobook, ABS ebook, ABS audiobook} is the position every surface opens at, and any surface's close/pause writes that surface's position into **all three** local representations plus the two ABS records. No representation is updated without the others being reconcilable to it.

## Consequences

- Readaloud listening moves the audiobook (local + ABS) and the ebook (sentence-precise); audiobook listening moves readaloud and the ebook; an offline listen on either side is honoured at the next entry without waiting for an ABS round-trip.
- The reader gains a dependency on the local audiobook store for entry reconciliation (today it only reads ABS).
- This is additive to the durable sweep (ADR 0030): the sweep still pushes whatever the flush left dirty; the flush just guarantees the local stores hold the full position at the moment a surface closes.
- Sentence-precise ebook persistence relies on the text-anchored `fragmentLocator`; translating that to the ABS ebook **CFI** for the server push may degrade to the column locator where a text anchor can't be converted — local save and resume stay sentence-precise regardless.

## Alternatives considered

- **Per-sentence canonical drive (push every narrated sentence into the reading position live).** Most "live," but it continuously rewrites the canonical in the erase-saga path and multiplies writes. Reconcile-on-entry / flush-on-exit (plus the existing ~10s follow loop for the audiobook) gets the positions in sync at every boundary that matters, with far less churn and risk. Rejected as the primary mechanism.
- **Keep reconciling only through ABS (let the durable sweep carry locals to ABS, then GET).** Simpler, but leaves the offline-listen-then-readaloud case stale until a network round-trip completes, which is exactly the user-visible failure. Rejected.

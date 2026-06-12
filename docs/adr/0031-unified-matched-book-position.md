# ADR 0031 — Unified matched-book position: reconcile-on-entry, flush-on-exit across ebook, audiobook, and readaloud

**Status:** Accepted
**Relates to:** [ADR 0019](0019-three-peer-unified-canonical-progress-sync.md), [ADR 0029](0029-audiobook-direct-abs-streaming-audio-led-sync.md), [ADR 0030](0030-durable-offline-progress-reconcile.md).

## Amendment — cross-EPUB index build triggers: download-complete primary, open-time self-heal, no library-refresh loop

The cross-EPUB index (ADR 0019/0021 — the per-chapter ABS↔Storyteller char map needed for the full ebook-CFI translation) was previously (re)enqueued for **every** Confirmed link on **every** library refresh (i.e. each navigation into a library). That is wasteful: it iterates all matches and launches a coroutine per link on every entry, almost all of which short-circuit (the build only does real work once the Storyteller bundle is locally present). The bundle is the index's only prerequisite the builder can't fetch itself, and it arrives at exactly one deterministic moment — when the user downloads readaloud.

**Trigger set:**

- **Primary — readaloud bundle download-complete.** On a successful readaloud download the bundle is present; enqueue the build right there (`LibraryItemDetailViewModel`). This is the natural moment and the only one needed in the common path.
- **Self-heal on reader-open AND player-open, at one chokepoint.** `ReaderSyncFactory.createIfApplicable` is the single place both surfaces ask for the full coordinator. When it reaches the index lookup having already confirmed the link and that **both EPUBs are cached**, a missing index means "buildable but not built" — so it enqueues the build there and returns `null` (stay single-peer). This one seam covers both surfaces and recovers the cases download-complete can't: a build that failed/deferred at download time, an EPUB re-upload (checksum change invalidating the index), or a bundle present from outside the in-app download flow.
- **Removed — the library-refresh loop.** `ReadaloudMatchingService.reconcileLinks` no longer enqueues builds.

**Feedback on a missing index.** Missing-index is otherwise *silent* (sync quietly degrades to single-peer; it is **not** the cause of missing readaloud highlights, which are text-anchored from the bundle and index-independent). The chokepoint above logs a `WARN` naming the matched item and that sync degraded to single-peer / a build was enqueued — the trace that explains "sync works only sometimes."

Callers depend on a narrow `CrossEpubIndexBuildTrigger` seam (implemented by `CrossEpubIndexBuilderService`), not the I/O-heavy service, keeping the trigger sites unit-testable.

## Amendment — the bundle fragment is the pivot; the ebook page-canonical is never translated directly to audio

Live testing exposed a concrete defect: Play-from-here at a sentence ~2/3 down a page synced the audiobook to a point **50–80 s earlier** — the **first sentence of the page**, not the selected one. Root cause (confirmed against the real Martian bundle, two data points): the audiobook position was written from the **ebook page/canonical coordinate**. A Readium locator is only **page/column-level**, so translating it to audio (`canonicalToAudioSeconds`) resolves via `fragmentAt(pageProgression)` to the **page-top sentence**. The error equals the narration time of the text between the page top and the real sentence — tens of seconds to ~a minute, varying with how far down the page the position is (hence not constant, not proportional). The timelines and SMIL math are otherwise exact (bundle total 39,214.6 s == ABS audiobook 39,214.5 s; per-file duration estimate verified to 0.00 s drift via `ffprobe`), so this was purely the wrong *source coordinate*, not a translation error.

**Corrected rule — one pivot, no special cases.** For a matched book with the bundle present, the sync position is **always a bundle SMIL fragment (a sentence)**, deduced as:

```
fragment = activeNarratedFragment            // readaloud narrating: the exact spoken sentence
        ?: firstSentenceOnPage(pageLocator)  // otherwise: the page-top sentence (fragmentAt of the page progression)
```

Every ebook↔audiobook transfer routes **through that fragment**:
- **readaloud / reading → audiobook:** `fragment → seconds` (SMIL, exact).
- **audiobook → ebook + readaloud:** `seconds → fragment` (SMIL, exact) → ebook text anchor; the fragment **is** the readaloud position.

The ebook **page-canonical is only ever an *input* to deduce the page-top fragment** — it is **never translated directly to a final audio time**. (The ebook's own stored locator stays page-level — that's inherent to a Readium locator — but the *audio* it maps to is always taken from the fragment, not the page.) This supersedes the page-canonical audiobook write in the slice-6 dual-write, and prefers the bundle-SMIL fragment route over the cross-EPUB-index path for audiobook↔ebook (sentence-sharp **and** index-free). It also means there is no "don't sync" case: as long as the readaloud exists, a fragment can always be deduced, even during silent reading.

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

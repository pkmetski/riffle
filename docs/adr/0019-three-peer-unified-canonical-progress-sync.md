# ADR 0019 — Three-peer unified-canonical Progress Sync

**Status:** Accepted
**Supersedes:** Extends [ADR 0008](0008-last-update-wins-progress-sync.md) for the matched-readaloud case; ADR 0008's two-peer rules remain in force for ABS-only books.

## Context

Storyteller integration introduces a third remote position holder for any book that is a readaloud matched across the two backends. Where ADR 0008 reconciles one local reader against one remote (ABS ebook progress), a matched book now has three independent remotes — each with its own clock and its own coordinate system:

- **ABS ebook progress** — a CFI in the ABS-served EPUB plus a book-wide float.
- **ABS audiobook progress** — a time offset (seconds) into the audiobook file.
- **Storyteller position** — a position record native to Storyteller's `/api/v2/books/:id/positions` endpoint.

All three change independently in the wild: a user can listen on ABS's audiobook app on their phone, read on Storyteller's web reader on a desktop, and read in Riffle on a tablet — concurrently across days. Riffle must reconcile incoming changes from any of them, and propagate outgoing changes to all of them, without the user being prompted.

The two coordinate-conversion problems are themselves load-bearing:

- **Audiobook seconds ↔ text position** is resolvable via the EPUB Media Overlay SMIL — the file Storyteller produces specifically for this purpose. Exact.
- **ABS-EPUB CFI ↔ Storyteller-EPUB position** requires the cross-EPUB character-position index described in ADR 0013's translator generalised across two different EPUB files for the same logical book. Best-effort; landed-on as the chosen design after weighing it against "skip ABS ebook progress entirely" in [the Storyteller-integration design discussion].

## Decision

For every applicable remote position holder of the open book, Riffle runs a single reconciliation cycle every ~30 seconds and immediately on reader resume. The cycle uses a **single canonical reader position** and a **single `localUpdatedAt`** — it does not maintain per-remote local timestamps.

**Cycle steps:**

1. **Identify applicable remotes** for the open book:
   - ABS-only book: `{ABS ebook}` (today's ADR 0008 case).
   - Storyteller-only book: `{Storyteller}`.
   - Confirmed-matched book, opened from **either** side, with sync prerequisites cached (Storyteller EPUB bundle present, cross-EPUB index built or buildable): `{ABS ebook, ABS audiobook, Storyteller}`. Side determines which EPUB the reader displays and what playback affordances are exposed (readaloud audio + highlight on the Readaloud side only — see ADR 0020), but it does **not** determine the sync remote set.
   - Confirmed-matched book whose sync prerequisites are not yet cached: falls back to its side's single-peer set (`{ABS ebook}` on ABS side, `{Storyteller}` on Readaloud side) until the prerequisites arrive, then upgrades to the three-peer set on the next cycle.
2. **GET each remote in parallel.** Per-target failures are isolated — a failing GET excludes that target from the inbound comparison only; the others still participate.
3. **Inbound winner:** find the maximum `lastUpdate` among `{localUpdatedAt} ∪ {successful remotes}`. If a remote wins, convert its position to the canonical reader position (via SMIL or the cross-EPUB index as appropriate), jump the reader silently, and set `localUpdatedAt = winner.lastUpdate`.
4. **Outbound:** for every remote that is now stale (its last seen `lastUpdate` is older than `localUpdatedAt`), derive its native coordinate from the canonical position and PATCH it. PATCH failures are isolated per target — a failed PATCH leaves that target stale for the next cycle.

The canonical reader position is the Readium `Locator` on whichever EPUB the reader is currently displaying — the ABS EPUB when reading from the ABS side, the Storyteller EPUB when reading from the Readaloud side. Outbound translation to the other peers is symmetric: from ABS-EPUB CFI, push to ABS ebook directly, translate to Storyteller-EPUB position via the cross-EPUB index then PATCH Storyteller, and translate further to audio seconds via SMIL then PATCH ABS audiobook. The composed translation is the same machinery used on the Readaloud side, applied in the other direction.

## Invariant

> The cycle has one inbound winner and at most one reader jump per cycle. All outbound PATCHes within a cycle are derived from the same canonical position.

## Alternatives considered

**Three independent last-update-wins cycles in parallel** — each remote reconciled against its own local timestamp. Rejected: the reader could jump twice within one cycle if two remotes were newer with different positions, and three local timestamps drift from each other across partial failures.

**ABS-as-source-of-truth (Storyteller is push-only)** — GET only ABS, PATCH ABS + push to Storyteller. Rejected because a user reading concurrently in Storyteller's own web reader is a legitimate scenario; we should not silently overwrite their position on next sync.

**Storyteller-as-source-of-truth (ABS is push-only)** — symmetric inverse. Rejected for the same reason on the ABS side: ABS clients (web reader, ABS mobile apps) are also legitimate inbound writers, especially for ABS audiobook progress when the user listens on their phone.

**Sync only audiobook progress to ABS (skip ABS ebook progress entirely)** — the SMIL gives us audio seconds exactly; skip the lossy cross-EPUB CFI translation. Rejected: ABS's web reader stores ebook position separately and other ABS clients would observe a drifting/stale ebook position for readalouds. The user's stated requirement is "any progress must be synced to audiobookshelf book and audiobook, and also storyteller's db."

## Consequences

- The sync cycle becomes per-remote-set rather than per-server, with the set computed from match state and prerequisite-cache state at reader-open time. Open-side determines what the reader *displays* and which playback affordances are visible; it does not determine the sync remote set.
- `localUpdatedAt` remains a single value (no per-remote timestamps); each remote tracks its own last-seen `lastUpdate` only for staleness comparison within the cycle.
- **Sync prerequisites for a matched book are decoupled from playback prerequisites.** Storyteller's position record is a Readium Locator anchored to *Storyteller's* publication manifest — pushing or interpreting one requires Storyteller's EPUB bundle (a few MB), but not its audio bundle (potentially multi-GB). The cross-EPUB character-position index requires the ABS EPUB too. Both prerequisites are small, so they are fetched **eagerly on Confirmed-match creation** (see ADR 0021), not lazily on first cross-domain need; the index is built the first time both EPUBs are local. The audio bundle remains opt-in via the explicit Readaloud-side "Download readaloud audio" action.

### Cross-EPUB index lifecycle

The index is built lazily, persisted in Room, and gracefully degrades when its inputs aren't reachable:

- **When built:** triggered eagerly on Confirmed-match creation (auto-Confirm at Tiers 1/2 or user-Confirm via the review queue — see ADR 0021), and also rebuilt opportunistically on the first sync cycle that needs it if a prior build was deferred. Build steps: ensure both EPUBs are locally cached (fetch the Storyteller EPUB bundle and/or the ABS EPUB as needed); walk both chapter-by-chapter; emit per-chapter character maps.
- **Where stored:** a `CrossEpubIndex` Room table keyed by `(absEpubChecksum, storytellerEpubChecksum)` with serialised per-chapter character maps.
- **Invalidation:** if either side's checksum changes (the server re-uploaded the EPUB), the stored index becomes a miss; the next cycle rebuilds.
- **Failure modes:** if either EPUB cannot be downloaded at build time, the build is deferred and the matched-book cycle gracefully degrades to single-peer for whichever side is open (`{ABS ebook}` from the ABS side, `{Storyteller}` from the Readaloud side) until the prerequisites become available. Per-target failure isolation applies within the three-peer cycle once prerequisites are present: a failed individual PATCH/GET doesn't poison the cycle.
- **Independence from sync correctness:** missing prerequisites ⇒ degrade to single-peer; never push a wrong cross-domain position.
- Partial connectivity is a normal case, not a degraded one. If ABS is unreachable but Storyteller is reachable, the cycle continues with `{Storyteller}` as its remote set, and the ABS targets stay stale until they're reachable again. The "skip the entire cycle on GET failure" rule from ADR 0008 is replaced by per-target isolation.
- Audio-led canonical position: when audio plays in background, page turns can't update the canonical position because the reader isn't visible. The SMIL drives the canonical position from audio time; outbound PATCHes follow normally. Returning to the foreground jumps the visible page to the canonical position.
- Reading Sessions are independent of this cycle (see the Storyteller-integration design): an ABS ebook session ticks whenever the reader is open, an ABS audiobook session ticks whenever audio plays, a Storyteller session ticks whenever either is true. Concurrent ticks are intentional.
- ABS-only books are unaffected — their cycle reduces to ADR 0008's exact behaviour with no per-target rules of consequence.

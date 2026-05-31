# ADR 0021 — Client-side incremental Storyteller↔ABS matching with a review queue

**Status:** Accepted

## Context

[ADR 0020](0020-storyteller-as-peer-server-and-readaloud-library.md) establishes Storyteller as a peer Server type contributing a Readaloud Library, and that a Confirmed-matched book has cross-Server progress sync ([ADR 0019](0019-three-peer-unified-canonical-progress-sync.md)) and ABS-sourced display metadata. The mechanism that produces those Confirmed matches must:

- Pair Storyteller books with ABS Library Items automatically, with no user setup for the common case.
- Avoid wrong matches at all costs. A wrong link corrupts three-way progress sync — Riffle would push the user's reading position into an unrelated ABS item, polluting that other book's progress on every ABS client. The asymmetric cost (wrong > missing) is the governing constraint.
- Cope with imperfect metadata: ISBN/ASIN may be missing on either side, titles may differ in punctuation or subtitle inclusion, author name conventions vary ("Smith, John" vs "John Smith").
- Give the user a way out when the auto-matcher can't decide, including when both sides have poor metadata.

The matching layer is not a user-facing concept by default — the success case is invisible. But total invisibility forces the matcher to fail-closed on ambiguity (better no link than a wrong one), which would silently drop genuine matches. A bounded review surface is the relief valve.

## Decision

Matching runs **client-side in Riffle**, **incrementally** as Storyteller and ABS metadata flow in, with results persisted in the local database. The Storyteller input set is the **completed-readaloud set** surfaced by ADR 0020's network-layer filter — in-progress and unaligned Storyteller books are not matching candidates because they are not visible to Riffle at all. Each Storyteller readaloud carries a match state in `{Confirmed, Pending Review, Unmatched}`.

### Auto-match ladder

For each Storyteller book, candidates are sought across every configured ABS Server. The tiers, evaluated strongest to weakest, are:

- **Tier 1 — exact identifier match → Confirmed.** ISBN-13 or ASIN, normalised (strip hyphens and whitespace; uppercase ASIN). If exactly one ABS item matches, the book is Confirmed.
- **Tier 2 — exact normalised title + author → Confirmed.** Case-insensitive, whitespace-collapsed, punctuation-stripped; author normalisation treats "Smith, John" and "John Smith" as equivalent. If exactly one ABS item matches, the book is Confirmed.
- **Tier 3 — fuzzy title + author above threshold → Pending Review.** Token-set similarity ≥ 0.85 on both title and author. All candidates above threshold are surfaced together for user decision.
- **Tier 4 — no candidates → Unmatched.** No link is created; the book stays available for manual pairing.

### Collisions

If Tier 1 or Tier 2 produces **more than one** ABS candidate (e.g. the same ISBN on two configured ABS Servers, or two ABS items with identical normalised title+author), the result is demoted to **Pending Review**, not auto-Confirmed. A confident auto-link is only created when exactly one candidate clears the tier.

### Review queue (Settings → Storyteller Server → Readaloud matches)

The review surface shows three sections:

- **Pending Review.** For each Storyteller book, the auto-matcher's candidate ABS items with their scores. Per-candidate actions: **Confirm**, **Dismiss this candidate**. Per-book action: **No match — don't ask again**.
- **Unmatched.** Every Storyteller book the auto-matcher couldn't place. Per-book action: **Match manually...** — opens a picker that searches Library Items across every configured ABS Server.
- **Confirmed.** Every Confirmed link, with the matched ABS title shown. Per-link action: **Unlink**.

A secondary entry point is the **Readaloud-side Library Item Detail Screen**, which shows a small footer line: "Linked to: *[ABS title] · [Library]*" with an unlink icon when Confirmed, or "Not linked to an ABS book — *Pair manually*" when Unmatched.

### Persistence and stability

- A `ReadaloudLink` row in the local database records the pair (Storyteller book uuid, ABS Server uuid, ABS Library Item id) and a `userConfirmed` flag.
- A `ReadaloudCandidate` row records each Pending-Review candidate.
- A `ReadaloudDismissal` row records "No match — don't ask again" decisions and per-candidate dismissals to prevent re-surfacing.
- `userConfirmed = true` links (both auto-Confirmed-by-collision-free-tier-1-or-2 and user-Confirmed) are treated as **sticky**: the auto-matcher does not re-evaluate or change them on subsequent runs. Auto-created links without user touch are stable but can be re-evaluated on metadata change.
- **Confirm side-effect — sync-prerequisite fetch.** Creating a Confirmed `ReadaloudLink` (auto or user) enqueues a background fetch of the Storyteller EPUB bundle (few MB) for that book and a build of the `CrossEpubIndex` against the ABS EPUB (fetched if not already cached). These are ADR 0019's prerequisites for three-peer sync from either side. The audio bundle is not fetched here — it stays explicit-opt-in via the Readaloud-side action. Failure to fetch either small bundle is non-fatal: the matched book reverts to single-peer sync on whichever side the user reads from, and the fetch retries on the next refresh.
- Incremental: matching evaluates only new/changed Storyteller books and only against new/changed ABS items. Library refresh and Server-add trigger re-evaluation of the affected scope.

### Side of run

Matching is local-only. Neither Storyteller nor ABS is asked to perform matching server-side. The two backends remain independent of each other; the Riffle client is the only place the cross-Server identity lives.

## Alternatives considered

**Auto-pick the highest-scoring candidate at Tier 3 (no review queue).** Rejected: the wrong-link cost (corrupted three-way sync into an unrelated ABS item) is higher than the missing-link cost (single book unsynced). Demoting to Pending preserves the user's data integrity.

**Server-side matching (Riffle backend or Storyteller plugin).** Rejected: introduces a third component, requires either backend to take a dependency on the other, and the on-device matching cost is negligible.

**Single global confidence score** rather than a tier ladder. Rejected: identifier matches and title-author matches have qualitatively different reliability and should not be averaged. Tiers are explicit.

**Pure invisible matching (no review surface).** Rejected: forces Tier 3 to fail-closed entirely, silently dropping genuine matches with imperfect metadata on either side.

**Always require manual matching.** Rejected: defeats the purpose of "behind the scenes" matching for the high-confidence case which is the majority of well-curated libraries.

## Consequences

- Riffle's local database gains three tables (`ReadaloudLink`, `ReadaloudCandidate`, `ReadaloudDismissal`) with a Room migration.
- A new Settings surface is needed per Storyteller Server (the Readaloud matches review queue).
- The Readaloud Library Item Detail Screen gains the link-state footer.
- Matching is bounded work per refresh — candidate searches use indexed metadata fields, evaluated incrementally. No background sweep of the full library on every open.
- The matching algorithm itself is intentionally conservative in v1. Richer signals — narrator name, series + position, audiobook duration vs estimated word count, language — are deferred. The data model accommodates them as added scoring inputs without a schema change.
- A wrong auto-Confirm at Tiers 1 or 2 (e.g. duplicate ISBN entries on ABS with one being a typo) is correctable from the Confirmed section of the review queue via Unlink.
- Re-running the matcher does not disturb user decisions: Confirmed-by-user links are sticky, Dismissals persist.
- Matching is independent of [ADR 0019](0019-three-peer-unified-canonical-progress-sync.md) — it produces the link state that ADR 0019's cycle reads to decide its remote set.

# ADR 0027 — Facet filtering (Filtered Books Screen) runs client-side over synced items, not via the ABS filter API

> **Amended by [ADR 0041](0041-source-and-service-abstractions-replace-server.md)** — the "synced items" set is now the active **Source**'s catalog cache (populated when the Source implements `OfflineBrowseCapability`) or the acquired-items set otherwise. Client-side facet compute is unchanged.

**Status:** Accepted

## Context

The [Library Item Detail Screen] makes metadata values tappable — author, series, published year, genre, language, and the [Readaloud] badge — each navigating to a [Filtered Books Screen] that lists the matching books in the current Library. We needed to decide where "matching" is computed.

ABS exposes a server-side filter API (`/api/libraries/:id/items?filter=<facet>.<base64-value>`) that filters by author *entity id*, genre, etc. The obvious-looking choice is to call it. We rejected it.

## Decision

**The Filtered Books Screen filters the already-synced `library_items` table locally.** Every Library's items are fully synced into Room (the All Books Tab reads them), so a facet screen is a Room query scoped to the current Library — reusing the exact Series/Collection-detail pattern (observe Room → cover grid). No new network calls, no new DTOs.

Matching rules:
- **Author** — the displayed byline is split into one tappable chip per author on `", "`; matching is a name-**token** match against the flattened `author` string. (A single author whose name contains a comma — e.g. "King, Jr." — splits wrongly; rare because ABS keeps the "Last, First" sort form in a separate field, so the displayed `authorName` is "First Last" joined by `", "`.)
- **Series** — routes to the existing Series detail screen; the tap matches on the series **name only** (the stored `seriesName` is `"<name> #<sequence>"`, so the `#N` is stripped for lookup but kept for display).
- **Published year / language** — exact-match, single chip, no ranges.
- **Genre** — substring match against the stored delimited genres string.
- **Readaloud** — "has a Readaloud" matches against the linked-ABS-item set (`ReadaloudLinkRepository`); reachable **only** by tapping the badge on a book that has one (self-gating: no Storyteller server → no badges → feature invisible). No library-wide filter chip.

## Consequences

- **Works offline and is instant** — consistent with the app's local-first, first-class-offline design; the rest of the library UI already filters Room locally (search, offline availability).
- **No author-identity plumbing.** Riffle stores only `authorName` (a string), not ABS author ids. The token-match accepts rare multi-author/comma-in-name imperfection in exchange for not introducing author-id sync.
- **Adding `language`** is the only new data work: it must be plumbed ABS DTO → Room entity (a migration) → domain → detail display.

## Alternatives considered

- **ABS server-side filter API.** Rejected: it does not work offline, and author filtering keys on an author *id* that Riffle does not store — forcing extra plumbing — for a robustness gain (multi-author identity) that the token-match approximates well enough. Breaks local-first consistency.

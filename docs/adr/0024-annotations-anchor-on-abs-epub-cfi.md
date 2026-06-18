# ADR 0024 — Annotations anchor on ABS-EPUB CFI, ABS-side only

**Status:** Accepted

## Context

Highlights, Notes, and Bookmarks (collectively [Annotation]s) need a position anchor that is stable across font/screen changes, survives EPUB re-uploads as best it can, and round-trips losslessly for future sync. Reflowable EPUBs have no fixed pages, so a page number is not a usable anchor. A complication is that a Confirmed-matched Readaloud exists as **two different EPUB files** — the ABS EPUB and the Storyteller EPUB — with different internal structure and therefore different CFIs (see [ADR 0019](0019-three-peer-unified-canonical-progress-sync.md), [ADR 0023](0023-storyteller-synced-bundle-is-the-readaloud-audio-source.md)). The translation between them is best-effort.

## Decision

Every Annotation anchors on an **EPUB CFI** — a CFI *range* for Highlights/Notes, a CFI *point* (the reader's top-of-viewport `currentLocator`) for Bookmarks — plus a stored text snippet and chapter href as a human-readable fallback and re-anchoring aid. The CFI is the load-bearing, irreversible anchor.

The **ABS EPUB is the canonical coordinate system** and the ABS Library Item is the key. Storyteller is only a [Readaloud] provider, so:

- A Storyteller-only (unmatched) book carries **no** Annotations.
- Annotations are created and displayed **only while reading the ABS side** in v1. The Readaloud (Storyteller) side stays pure playback.

CFI is the same coordinate family ABS already stores in `mediaProgress.ebookLocation` and that [ADR 0013](0013-epub-cfi-translator-for-progress-sync.md)'s `EpubCfiTranslator` operates on — so a future native ABS annotation API maps mechanically.

## Considered options

- **Per-logical-book, crossing the ABS↔Storyteller match via ADR 0019's cross-EPUB index.** Rejected as the *storage* model: the index is best-effort, and anchoring synced data (which must round-trip exactly, forever) on a lossy translation would permanently misplace highlights. Crossing the match to *display* ABS-anchored annotations on the Readaloud side is left as a deferred enhancement, where the lossiness is ephemeral and confined to a second-class surface.
- **Per-EPUB keying** (annotations scoped to whichever publication they were made in, never crossing). Rejected because, with ABS as the canonical home, keying by the ABS item gives the same exactness while letting annotations belong to the logical book.
- **Page-number anchors.** Rejected for EPUB — pages aren't stable under reflow. (Page anchors remain the natural choice if/when PDF annotations are added.)

## Consequences

- Annotations require an ABS EPUB; the affordance is absent on Storyteller-only books and on the Readaloud reading side in v1.
- Synced annotation data is always exact ABS-EPUB CFI; no best-effort translation ever enters the stored/synced record.
- EPUB only in v1. PDF (page + rect anchors, a different anchor type the W3C format can also carry) is out of scope.

## Amendment (2026-06-17): colour palette + rendering (#70)

The highlight colour is one of four tokens — `yellow` (default), `green`, `blue`, `pink` —
modelled by `HighlightColor` (`core/domain`). Tokens are stored verbatim in
`AnnotationEntity.color`; an unknown/missing token resolves to yellow (sync forward-compat).
The four hues match the readaloud palette for visual consistency but are a separate, smaller
vocabulary (no PURPLE, yellow default) decoupled from `ReadaloudHighlightColor`.

Rendering reuses the shared `HighlightTintStyle` decoration + `tintForTheme()`: the reader theme
bakes per-theme alpha into the base hue (~45% on Dark/DarkDim, ~30% on Light/Sepia) so a highlight
stays legible in any theme. Recolour updates the row in place (bumping `updatedAt`); delete sets the
`deleted` tombstone (bumping `updatedAt`) and tombstoned rows are excluded from the live query, so
they never render.

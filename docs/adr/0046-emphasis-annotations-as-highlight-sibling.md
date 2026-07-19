# 0046 — Emphasis annotations as a Highlight sibling

**Status:** Accepted
**Date:** 2026-07-17

## Context

Users want to apply typographic emphasis — **bold**, *italic*, <u>underline</u>, <s>strike</s> — to arbitrary text ranges in an ebook, on top of what the publisher's CSS already renders. This is a distinct gesture from highlighting a passage: the user is re-emphasising the text the way an editor would, not saving it for later revisit. The gestures compose: users want to bold the key phrase *inside* a yellow highlight, or strike through a claim they disagree with, or underline a term they want to look up.

Riffle already ships three annotation types — [Highlight], [Note] (embedded in Highlight), [Bookmark] — plus an implementation detail `TYPE_IMAGE`. Adding text-style emphasis raises several design questions that all cascade from one root: **is emphasis a new colour on Highlight, a new annotation sibling, or rich formatting inside Notes?**

The rest of the design tree hangs on that first fork:

- How are combinations of styles stored?
- How does emphasis interact with publisher CSS (already-italic foreign phrases, publisher-bold headings)?
- Does emphasis surface in the [Annotations View] (the "curated passages" tab)?
- What are the auto-merge rules when the user emphasizes adjacent ranges?
- What happens when the user deletes a range that carries both a highlight *and* emphasis?

## Decision

Ship **Emphasis** as a new [Annotation] type, sibling to [Highlight], with the following contract:

### 1. Storage — separate row per gesture, set-valued styles

Emphasis is `TYPE_EMPHASIS` in `AnnotationEntity` — a sibling of `TYPE_HIGHLIGHT`, `TYPE_BOOKMARK`, `TYPE_IMAGE`. One row per gesture, carrying a non-empty subset of `{BOLD, ITALIC, UNDERLINE, STRIKE}` in a new `emphasisStyles` column (comma-separated tokens, nullable on non-emphasis rows). A range that carries *both* a highlight and emphasis is two rows.

**Rejected:** a sixth colour on Highlight (mutually exclusive with real highlighting, and staples four style values into the `HighlightColor` vocabulary), and a single row with both a colour and a styles set (destroys per-axis provenance for W3C sync).

### 2. Rendering — literal overlay, never a toggle

Applying `bold` always sets `font-weight: bold` for the range, regardless of what the publisher's CSS renders as the baseline. The stored intent and the on-screen result are the same string of CSS — a synced annotation renders identically on devices whose fonts or dark-mode inversion CSS differ.

Silent no-ops (bolding already-bold text) are prevented at the UX layer: the action sheet greys out styles the baseline already provides on the *entire* selection, with a small "already italic" caption. A *partial* overlap with a publisher-styled run leaves the chip enabled and applies literally.

**Rejected:** word-processor toggle semantics (bold-over-bold = regular). Cleaner responsive feel, but decouples stored intent from rendered output, which breaks the sync identity we need across devices with different font stacks.

### 3. Coexistence — freely layered with Highlight, no forbidden combos

An Emphasis row and a Highlight row over the same CFI range are two independent annotations. Any subset of `{bold, italic, underline, strike}` combines with any highlight colour. No cap on the styles set size; all four styles + a highlight is a legal state.

### 4. Action sheet — two axes, symmetric removal

The bottom sheet has a Highlight-colour row (five swatches: `∅`, yellow, green, blue, pink) and an Emphasis-chips row (`B`, `I`, `U`, `S`). They're independent axes. Removal is axis-symmetric:

- Tapping `∅` removes the Highlight only.
- Toggling every Emphasis chip off leaves an empty set that garbage-collects on sheet dismiss (so the row's provenance survives an in-flight edit).
- The explicit **Delete** action removes every annotation the range carries — both rows if both are present.

### 5. Auto-merge — same shape as Highlight merge, keyed on styles

Two Emphasis rows auto-merge iff their `styles` sets are **identical** *and* their ranges are adjacent (touching or with only whitespace in the gap), chain-merged loop-until-stable — the same mechanism as Highlight auto-merge, with `styles` playing the role of `color` and the no-note gate collapsed. Merging is blocked when a figure sits in the gap, for the same "two separate gestures preserved" rationale as `HighlightMergeTest`'s cross-figure rule.

Rows with *different* `styles` sets never merge — they stay independent and the renderer unions their styles character-by-character at paint time. Same-range writes edit the existing row (mirror of `highlightOverlapsAtSamePosition` → `emphasisOverlapsAtSamePosition`). Toggling a style off from a *partial* selection of a merged row shrinks the row's range to the un-selected side.

### 6. Annotations View — piggyback only, no query change

The Annotations View tab query and empty-state remain `TYPE_HIGHLIGHT`-only. Standalone Emphasis does *not* surface a book in the tab. But an Emphasis row whose CFI range overlaps a rendered highlight snippet is applied to that snippet's text in the elided reader (bold, italic, underline, strike as stored), so the "editor's" marks on top of "collector's" marks flow through automatically.

Emphasis-only usage is a reading-experience feature, not a review surface — consistent with how bold/italic work in a physical book. Widening the tab to include emphasis-only books is additive if usage warrants it later.

### 7. Format scope — EPUB only in v1

Emphasis anchors on EPUB CFI (ADR 0024), so PDF and CBZ are out of v1 for the same reason Highlights are. Emphasis gets page-anchored form when PDF annotations do.

### 8. No Note on Emphasis

A user who wants to attach a written note to an emphasized range creates a Highlight over the range too. Note remains an optional field on Highlight only.

### 9. Sync — additive W3C body

Emphasis becomes a W3C Web Annotation with a `TextualBody` bearing a `riffle:styles` extension carrying the set (`["bold","underline"]`). One W3C annotation per Emphasis row. Additive to the existing schema; no breaking change to blob-store or record-store target implementations.

## Consequences

- New `emphasisStyles` column and `TYPE_EMPHASIS` constant on `AnnotationEntity`. Migration adds the column with a nullable default; existing rows unaffected.
- `emphasisOverlapsAtSamePosition` write-time guard, mirroring the highlight one.
- Extended auto-merge logic in `HighlightMerge` (or a shared `AnnotationMerge`) parameterised on the "match key" — `color` for highlights, `styles` for emphasis, no-note-gate on highlights only.
- W3C codec learns a `TYPE_EMPHASIS` ↔ `TextualBody[riffle:styles]` round-trip.
- Reader's Readium `Decoration` layer gains a text-style decoration variant that injects inline CSS on the range's spans (in addition to the existing background-paint decorations).
- Elided reader's snippet renderer walks overlapping Emphasis rows and applies their union to each rendered snippet.
- Action sheet gains an Emphasis row with baseline-style detection via `getComputedStyle` on the selection's start element (same technique the reader already uses to capture `originFontFamily`).
- Annotations View unchanged.
- Cadence / Readaloud sentence highlight, Search decoration, `originFontFamily` capture — all layer freely with Emphasis; no mutex.

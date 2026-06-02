# Readaloud indicator icon — design

**Date:** 2026-06-02
**Status:** Approved design, ready for implementation plan

## Goal

Make books that have **synced narration** ("readaloud" — text highlighted while it's
read aloud) instantly recognizable in the ABS library, and replace the misleading
headphones icon with a purpose-drawn glyph that reads as *text + narration* rather than
*plain audiobook*.

A matched book should carry the same mark in three places:

1. **Library grid** — an overlay icon on the cover.
2. **Item detail** — an icon indicator near the title / in the readaloud row.
3. **Reader top bar** — the existing readaloud control.

This applies to **both ebook and audiobook** ABS items that have a confirmed
`ReadaloudLink`.

## Explicitly out of scope (shelved)

The earlier idea of a **dedicated readaloud launch button** on the library card —
opening the book and auto-starting playback, with a "not downloaded" error — is **not**
part of this work. No new launch button, no `startReadaloud` nav arg, no auto-play.
Readaloud continues to start the way it does today (the control inside the reader).

This spec is purely the **indicator/icon**.

## The icon

A single, purpose-drawn glyph: **an open book with sound arcs radiating upward**
("book + broadcast"). One glyph, not two Material icons stacked in a dot (that
execution turned to mush at badge size — see prototype history).

- Ships as a `VectorDrawable` resource (e.g. `core` or app `res/drawable/ic_readaloud.xml`),
  referenced via `painterResource(...)`. It replaces every current use of
  `Icons.Filled.Headphones` for readaloud.
- Stroke-style to match the outlined feel; tint follows the call site
  (`tertiary` on the list badge today, `onSurface`/themed in the reader, white on the
  cover overlay scrim).

Reference geometry (24×24, `strokeWidth ≈ 1.8`, round caps, `currentColor`) — to be
refined into the production drawable:

```
book:  M12 11.2c-1.5-1-3.6-1.6-5.7-1.6-.6 0-1.1.4-1.1 1v6.3c0 .5.5.9 1.1.9
       2.1 0 4.2.6 5.7 1.6 1.5-1 3.6-1.6 5.7-1.6.6 0 1.1-.4 1.1-.9v-6.3c0-.6-.5-1-1.1-1
       -2.1 0-4.2.6-5.7 1.6z
spine: M12 11.2v6.9
arc1:  M9 8.2c1.9-1.1 4.1-1.1 6 0
arc2:  M7.4 5.6c2.8-1.6 6.4-1.6 9.2 0
```

Interactive prototype (icon candidates + placement, served locally during design):
`.context/readaloud-proto/index.html`.

## Placement & style (chosen)

- **Badge style:** icon dot (circular dark scrim + white glyph). Not the pill+label.
- **Corner:** top-right.
- Cover overlay sits in the existing top-end overlay area of `BookCoverTile`, where the
  "C"/"D" cache/download badges already render. Scrim ≈ 28dp, glyph ≈ 16–17dp, for
  contrast over arbitrary cover art.

## Components & changes

### 1. New drawable
`res/drawable/ic_readaloud.xml` — the book+broadcast vector. Add a single shared
accessor (e.g. a `painterResource`/`ImageVector` helper) so all three call sites use
the same source.

### 2. Replace existing headphones usages (icon swap, no behavior change)
- `LibraryItemsScreen.kt:824` — list-row readaloud badge.
- `LibraryItemDetailScreen.kt:340` — item-detail readaloud indicator/row.
- `EpubReaderScreen.kt:394` — reader top-bar readaloud control.

Drop the now-unused `Icons.Filled.Headphones` imports.

### 3. New: cover overlay in the grid view
`BookCoverTile` (`LibraryItemsScreen.kt:404`) currently receives no readaloud signal.

- Add a `hasReadaloudLink: Boolean` parameter to `BookCoverTile`.
- Thread `linkedItemIds` from the screen → `CoverGrid` → `BookCoverTile`
  (`hasReadaloudLink = item.id in linkedItemIds`), mirroring how `LibraryItemCard`
  already does it at `LibraryItemsScreen.kt:292`.
- When true, render the icon dot at `Alignment.TopEnd`. Decide ordering relative to the
  existing C/D badges (readaloud icon first / leftmost is the likely choice so it's not
  pushed off-row).

### 4. Audiobook recognizability (verify-first prerequisite)
The indicator is driven by `readaloudLinkRepository.observeLinkedAbsItemIds()`. Audiobook
items only appear if the matcher actually creates a `ReadaloudLink` for the audiobook
ABS item (one Storyteller book can match multiple ABS items — ebook + audiobook).

- **Verify** whether audiobook ABS items currently get linked.
- If they do, no extra work — the new overlay covers them automatically.
- If they don't, linking audiobook items becomes a prerequisite task (matching layer),
  otherwise audiobooks can never show the mark regardless of UI.

Audiobook items are **indicator-only**: they have no EPUB to render, so the reader
top-bar control does not apply to them — the cover overlay and detail indicator do.

## Accessibility

`contentDescription` updated to convey the concept, e.g. *"Has readaloud (synced
narration)"*, at every call site. The cover overlay carries the same description.

## Testing

- **Grid overlay:** a matched **ebook** shows the icon top-right; a matched
  **audiobook** shows it; an **unmatched** item shows nothing.
- **Detail:** matched item shows the new icon (not headphones).
- **Reader:** top-bar readaloud control renders the new icon; existing readaloud
  open/play tests still pass.
- **Drawable:** the vector inflates without error and is legible at 16–17dp
  (manual/screenshot check).
- Follow the project's harness-test conventions (`make harness-test` /
  `make harness-test-tablet`).

## Open items to confirm during planning

1. Audiobook linking (section 4) — verify before assuming the overlay "just works" for
   audiobooks.
2. Exact resource module location for the shared drawable (app vs. a core/ui module),
   following existing icon-resource conventions.
3. Final ordering of the readaloud dot vs. C/D badges in the top-end overlay row.

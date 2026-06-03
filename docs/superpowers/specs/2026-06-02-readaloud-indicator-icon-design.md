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

In addition, the item-detail screen gets a **dedicated "Download readaloud" button**
for matched ABS items, so the readaloud bundle (synced EPUB + audio) can be provisioned
without ever visiting the Storyteller library.

## Explicitly out of scope (shelved)

The earlier idea of a **dedicated readaloud _launch_ button** on the library card —
opening the book and auto-starting playback, with a "not downloaded" error — is **not**
part of this work. No launch-from-library button, no `startReadaloud` nav arg, no
auto-play. Readaloud continues to *start* the way it does today (the control inside the
reader). The new detail-screen button **downloads** the bundle; it does not launch
playback.

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
- **Corner:** top-right, **alone**. The existing "C"/"D" cache/download badges are
  **removed from grid covers for now** (product decision), so the readaloud icon is the
  only thing in that corner.
- Scrim ≈ 28dp, glyph ≈ 16–17dp, for contrast over arbitrary cover art.

Note on the removal: "C"/"D" are storage-state badges (Cached = pulled to evictable
cache by opening; Downloaded = explicitly saved, permanent). They convey *do I have this
on-device*, which is unrelated to readaloud. Dropping them is intentional and scoped
"for now" — the rendering is small and easy to restore. Cache/download **behavior** is
unchanged; only the on-cover badges are hidden.

## Components & changes

### 1. New drawable
`res/drawable/ic_readaloud.xml` — the book+broadcast vector. Add a single shared
accessor (e.g. a `painterResource`/`ImageVector` helper) so all three call sites use
the same source.

### 2. Replace existing headphones usages
- `LibraryItemsScreen.kt:824` — list-row readaloud badge → new icon (straight swap).
- `EpubReaderScreen.kt:394` — reader top-bar readaloud control → new icon. (Also gains the
  enable/disable behavior in §6 — not just an icon swap.)
- `LibraryItemDetailScreen.kt:340` — this headphones lives inside `ReadaloudFooter`, which
  is being restructured (see §5). It disappears with the footer; the detail's new indicator
  icon is added near the title instead.

Drop now-unused `Icons.Filled.Headphones` imports.

### 3. New: cover overlay in the grid view
`BookCoverTile` (`LibraryItemsScreen.kt:404`) currently receives no readaloud signal.

- Add a `hasReadaloudLink: Boolean` parameter to `BookCoverTile`.
- Thread `linkedItemIds` from the screen → `CoverGrid` → `BookCoverTile`
  (`hasReadaloudLink = item.id in linkedItemIds`), mirroring how `LibraryItemCard`
  already does it at `LibraryItemsScreen.kt:292`.
- When true, render the icon dot at `Alignment.TopEnd`.
- **Remove the existing "C"/"D" badges** from that top-end `Row`
  (`LibraryItemsScreen.kt:447–456`) so the readaloud icon sits alone in the corner.
  Leave `item.isCached` / `item.isDownloaded` on the model untouched — this is purely a
  rendering removal.

### 4. Audiobook recognizability (no extra work needed)
Confirmed: audiobook ABS items already get linked (visible in the Storyteller section of
global settings). The DAO query backing the indicator is
`SELECT absLibraryItemId FROM readaloud_links` (`ReadaloudLinkDao.observeLinkedAbsItemIds`)
— it returns **every** linked ABS item id regardless of media type, so audiobook items
flow into `linkedItemIds` and the new cover overlay covers them automatically. No
matching-layer change required.

Audiobook items are **indicator-only**: they have no EPUB to render, so the reader
top-bar control does not apply to them — the cover overlay and detail indicator do.

### 5. Detail screen: replace footer + add readaloud download button

For a matched **ABS** item:

**a. Replace the footer with the indicator icon.**
The `AbsHasReadaloud` case of `ReadaloudFooter` (`LibraryItemDetailScreen.kt:310–387`,
the "Readaloud available — open from …" row with the headphones) is removed. In its
place, the new icon sits next to the title as a compact indicator (matching the grid /
reader iconography).

- Scope: this replaces the **ABS-side** footer state (`AbsHasReadaloud`). The
  Storyteller-side footer states (`ReadaloudLinkedToAbs` unlink, `ReadaloudPendingReview`
  → review queue, `ReadaloudUnmatched` → manual pair) are shown on the *Readaloud library*
  item, not the ABS item — they are out of scope here and unchanged. **Confirm during
  planning** that no ABS-side item relies on those interactive states.

**b. Add a dedicated "Download readaloud" button to the `ActionRow`.**
A second round 40dp control next to the existing ebook `DownloadButton`, using the new
glyph. It **downloads the Storyteller bundle** (synced EPUB + audio) for the matched ABS
item — the work the existing code comment at `LibraryItemDetailScreen.kt:545–550`
explicitly deferred to "the next slice."

**Visual (chosen):** the circle's main icon is the **download arrow**
(`Icons.Default.ArrowDownward`, same as the ebook button) with the **readaloud glyph as a
small badge** in the bottom-right corner — so it reads clearly as *download* while the
badge marks it as the *readaloud* one (distinguishing it from the adjacent ebook
download). The badge glyph is sized generously (not a tiny dot) for legibility.

Behavior **mirrors the existing `DownloadButton`** exactly:

- **NotDownloaded:** outlined circle, arrow + readaloud-glyph badge, tap → download.
- **InProgress:** circular progress indicator.
- **Downloaded:** filled (`primaryContainer`) circle, **tap → remove** the bundle
  (second-tap-removes, same as the ebook button).
- **No size** is shown.
- **Offline/metered:** reuse the existing readaloud handling — disabled with the
  "Connect to download readaloud audio" affordance when there's no usable connection.

Visibility: only for ABS items with a confirmed `ReadaloudLink`. Not shown on Storyteller
(Readaloud library) items — their single `DownloadButton` already fetches the whole bundle
(EPUB + audio), per the `:545–550` comment; a second control there would delete the bundle
the reader uses.

**Critical: key off the Storyteller book id, not the ABS item id.**
The readaloud bundle is stored under the **Storyteller book id** and fetched from the
**Storyteller server** — not the ABS item id / ABS server. Evidence:
`ThreePeerReaderSyncFactory.kt:63` reads the cached bundle via
`cachedBytes(link.storytellerBookId)`; `ReadaloudAudioRepositoryImpl.downloadAudio`
fetches from `serverRepository.getActive()` and stores by the passed `itemId`.

Therefore the ABS-side button MUST resolve the book's `ReadaloudLink` and operate on the
**linked Storyteller identity**:

- **Downloaded state** = `isAudioAvailable(link.storytellerBookId)`.
- **Download** = fetch the bundle from the **linked Storyteller server** (by
  `link.storytellerBookId`), not the active ABS server. (Today's `downloadAudio`/
  `probeSizeBytes` use the *active* server; planning must add/route a path that takes the
  linked Storyteller server + token explicitly, since the active server here is ABS.)
- **Remove** = `removeAudio(link.storytellerBookId)`.

Consequence (intended): the ABS button and the Storyteller-library control share **one
bundle** keyed by Storyteller book id. A bundle already downloaded from the Storyteller
library shows as **Downloaded** on the ABS button; downloading from the ABS button shows
up on the Storyteller side; **removing from either frees the single shared file** (call
this out in tests).

Wiring (planning to detail):
- A readaloud `DownloadState` (NotDownloaded / InProgress / Downloaded) for the matched
  ABS item, keyed by `link.storytellerBookId` as above. Reuse the existing
  `ReadaloudAudioRepository` download/probe/remove logic (also used by
  `EpubReaderViewModel.onPlayTapped`) rather than duplicating it.
- `LibraryItemDetailViewModel` exposes: is-matched (+ resolved link), readaloud download
  state, and `onDownloadReadaloud` / `onRemoveReadaloud`.

### 6. Reader control: disable until downloaded (no error)

For a **matched ABS book**, the reader top-bar readaloud control is **always shown** but:

- **Enabled** only when the bundle is downloaded (`isAudioAvailable(link.storytellerBookId)`,
  per §5's keying).
- **Disabled** (greyed, not pressable) when the bundle is not downloaded. Pressing it does
  **nothing** — **no error, no message, no download prompt**. The user gets the bundle via
  the detail-screen download button (§5b); once downloaded, the control enables.

Scope:
- **Unmatched ABS book** — no readaloud control at all (unchanged; there is no readaloud).
- **Storyteller (Readaloud) book** — unchanged: control stays available with its existing
  download-on-play behavior; the disable rule does not apply.

Note this changes the ABS path from today's "hidden unless `isAudioAvailable(absItemId)`"
(which was effectively never true, since the bundle is keyed by Storyteller book id — see
§5) to "shown when matched, enabled when the Storyteller-keyed bundle is present." The
reader VM's `readaloudAvailable`/enabled signal must become matched-aware and keyed by the
linked Storyteller book id.

## Accessibility

`contentDescription` updated to convey the concept, e.g. *"Has readaloud (synced
narration)"*, at every call site. The cover overlay carries the same description.

## Testing

- **Grid overlay:** a matched **ebook** shows the icon top-right; a matched
  **audiobook** shows it; an **unmatched** item shows nothing. **No "C"/"D" badges**
  render on grid covers anymore.
- **Detail:** matched ABS item shows the new icon by the title (old "Readaloud
  available" footer gone); an unmatched item shows neither icon nor download button.
- **Readaloud download button:** appears only for matched ABS items; renders the three
  states (NotDownloaded / InProgress / Downloaded); tap downloads, second tap removes;
  disabled with the offline affordance when there's no connection. Not shown on
  Storyteller (Readaloud library) items.
- **Shared-bundle reflection:** a bundle downloaded from the **Storyteller library**
  shows as **Downloaded** on the ABS button (and vice-versa); **removing from either**
  side frees the one shared file (keyed by Storyteller book id).
- **Reader:** top-bar readaloud control renders the new icon. For a **matched ABS book**:
  control is **disabled** (not pressable, no error/message) when the bundle isn't
  downloaded, and **enables** once it is. Unmatched ABS book → no control. Storyteller
  book → unchanged. Existing readaloud open/play tests still pass.
- **Drawable:** the vector inflates without error and is legible at 16–17dp
  (manual/screenshot check).
- Follow the project's harness-test conventions (`make harness-test` /
  `make harness-test-tablet`).

## Open items to confirm during planning

1. Exact resource module location for the shared drawable (app vs. a core/ui module),
   following existing icon-resource conventions.
2. Confirm no ABS-side detail relies on the interactive footer states being removed
   (§5a) — i.e., the ABS item only ever shows `AbsHasReadaloud`.
3. Confirm the bundle download/remove API surfaced for `LibraryItemDetailViewModel`
   reuses the existing `ReadaloudAudioRepository` path without duplicating logic.

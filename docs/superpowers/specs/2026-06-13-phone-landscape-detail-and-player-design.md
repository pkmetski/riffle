# Phone-landscape: detail screen & audiobook player

**Date:** 2026-06-13
**Status:** Approved design, pending implementation plan

## Problem

A large phone in **landscape** is wide enough (≥ 840 dp) to cross the `WindowWidthSizeClass.Expanded`
breakpoint, so it renders the **tablet** layouts: a 280 dp `PermanentNavigationDrawer` pinned to the
left, the two-pane item-detail layout, and the vertical audiobook player.

On such a window this is wrong:

1. **Item detail** (ebook *and* audiobook) — the permanent drawer eats ~280 dp, and the cover ends
   up tiny because it competes for the short landscape height inside the 360 dp left pane.
2. **Audiobook player** — the drawer is shown, and `PlayerSurface` is a single vertical column with a
   `fillMaxWidth(0.72f)` square cover; in the short landscape height the cover pushes the transport
   controls off the bottom of the screen. The screen also shows no book details.

A real **tablet** (Expanded width in *either* orientation) is fine as-is and must not change.

## Discriminator: "phone landscape" vs "tablet"

A phone in landscape has `widthSizeClass == Expanded` **and** `heightSizeClass == Compact`
(height < 480 dp). A tablet has a taller height class in both orientations. So:

```
isPhoneLandscape = widthSizeClass == Expanded && heightSizeClass == Compact
```

`heightSizeClass` is not currently used anywhere in the app; it is available on the existing
`WindowSizeClass` already threaded through `MainScreen` and the screens. This keeps the tablet view
untouched (it never has Compact height).

## Changes

### 1. Hide the permanent drawer on detail + player (phone-landscape only)

`MainScreen.kt` already hides the permanent drawer panel on reader routes via
`hidePermanentDrawerPanel`. Extend it:

```
hidePermanentDrawerPanel = isReaderRoute(currentRoute) ||
    (isPhoneLandscape && isDetailOrPlayerRoute(currentRoute))
```

where `isDetailOrPlayerRoute` matches the `library_item_detail/…` and `audiobook_player/…` route
prefixes. `usePermanentDrawer` (= `isExpanded`) is unchanged, so the surface still gets the full
width; only the 280 dp sheet is suppressed. On a tablet `isPhoneLandscape` is false, so the drawer
stays. No new back/gesture behaviour — these routes are full-screen with their own back arrow.

### 2. Item detail — new phone-landscape layout (larger cover)

Add a third content composable, `LibraryItemDetailContentPhoneLandscape`, selected in
`LibraryItemDetailScreen` when `isPhoneLandscape`:

- `isPhoneLandscape` → `LibraryItemDetailContentPhoneLandscape`
- else `isExpanded` → existing `LibraryItemDetailContentTablet` (tablet, unchanged)
- else → existing `LibraryItemDetailContent` (phone portrait / small phone, unchanged)

Layout (a `Row` of two columns, mirroring mockup #1):

- **Left column** (fixed ~240 dp, non-scrolling): a large cover that fills the available pane height
  (`weight(1f, fill = false)` + `aspectRatio(2f/3f)`), then `ReadingProgressIndicator`, the
  `AudiobookDurationLine` (when listenable), then `ActionRow`. Moving title/author **out** of this
  column is what lets the cover be tall — that is the "larger cover".
- **Right column** (`weight(1f)`, vertically scrollable): `TitleWithReadaloudIndicator`,
  `AuthorByline`, `SeriesLine`, `CollapsibleDescription` (Summary), `MetadataLines`.

Reuses the existing sub-composables; no new metadata. Identical for ebook and audiobook (the action
row already adapts to `isReadable` / `isListenable`).

### 3. Audiobook player — two-column landscape layout (Option A) + book details

**Data.** Add two fields to `PlayerSurfaceState`:

- `facts: String? = null` — a one-line summary built by the view-model, e.g.
  `"Audiobook · 10h 53m · Science Fiction & Fantasy"` (duration + first genre(s); omit empties).
- `description: String? = null` — the book blurb (`item.description`).

`AudiobookPlayerViewModel` already loads the full `LibraryItem`, so it populates both into `meta`.
Both default to null, so nothing else changes.

**Layout.** Thread `windowSizeClass` from `MainScreen` → `AudiobookPlayerScreen`; compute
`twoColumn = heightSizeClass == Compact` and pass it to `PlayerSurface`.

- `twoColumn == false` (portrait phone, tablet) → **existing vertical layout, unchanged.** Details are
  not shown here (out of scope — the request was about the landscape view).
- `twoColumn == true` → a `Row`:
  - **Left column** (fixed ~280 dp, centered): square cover at a fixed ~150 dp (not `fillMaxWidth`),
    `title`, `author`, `currentChapterTitle`, then `facts` and a 3-line-clamped `description`.
  - **Right column** (`weight(1f)`, vertically centered): `ChapterSeekBar`, `DualTime`,
    `TransportRow`, and the speed pill — the existing controls, unchanged, just relocated. Because
    the controls own their own column they are always fully visible regardless of window height.

The back arrow and the swipe-down-to-read-along gesture in `AudiobookPlayerScreen` are unaffected
(they wrap the whole surface).

## Out of scope

- Real tablet layouts (Expanded width, taller height) — unchanged.
- Phone portrait detail and player — unchanged.
- Book details on the portrait player.
- Narrator (no field exists on `LibraryItem`); `facts` uses duration + genres.

## Testing

- Unit/Robolectric: `PlayerSurface` renders the transport controls when `twoColumn = true` (regression
  for the off-screen-controls bug); `facts`/`description` shown when present.
- `LibraryItemDetailScreen` selects the phone-landscape composable for Expanded-width + Compact-height
  and the tablet composable for Expanded-width + non-Compact-height.
- Manual device check on the Harness Medium Phone (landscape) and a tablet AVD for no-regression.

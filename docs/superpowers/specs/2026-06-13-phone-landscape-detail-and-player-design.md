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

## Discriminator: "phone landscape" vs "tablet" (revised)

> Revised after device review: the first cut kept the tablet layouts on a phone-landscape window
> and merely hid the drawer + added a bespoke detail layout. On a real device that looked wrong (a
> squished Listen button, and the library still showed the permanent sidebar *and* a hamburger). The
> simpler, correct framing is **"a phone in landscape is a phone"** — it must not get the tablet
> layout at all. That is also ADR 0019's stated intent.

The Tablet Layout now requires the window to be large in **both** dimensions:

```
fun WindowSizeClass.isTabletLayout() =
    widthSizeClass == Expanded && heightSizeClass != Compact
```

A large phone in landscape crosses Expanded width but is Compact in height (< 480 dp), so
`isTabletLayout()` is false → phone UI. A real tablet is taller in both orientations → true. This one
predicate (in `ui/TabletLayout.kt`) replaces the bare `widthSizeClass == Expanded` checks. ADR 0019
is amended to match.

## Changes

### 1. Phone-landscape uses the phone form factor

`MainScreen.kt`: `usePermanentDrawer = windowSizeClass.isTabletLayout()`. A phone in landscape
therefore gets the **modal** drawer (hamburger) like any phone — no permanent sidebar, so the
library no longer shows a sidebar *and* a hamburger. `hidePermanentDrawerPanel` reverts to
`isReaderRoute(currentRoute)` only. The tablet is unaffected (still non-Compact height → permanent
drawer).

### 2. Item detail — the existing phone layout (no new composable)

`LibraryItemDetailScreen` selects on `isTabletLayout()`:

- `isTabletLayout()` → existing `LibraryItemDetailContentTablet` (unchanged)
- else → existing `LibraryItemDetailContent` (single-column phone layout)

The phone layout already handles landscape (`fillMaxWidth(0.4f)` cover, single scrollable column), so
a phone in landscape now gets a clean, familiar layout with a larger cover than the old tablet caps —
without a bespoke composable. The custom `LibraryItemDetailContentPhoneLandscape` from the first cut
is deleted.

### 3. Audiobook player — two-column landscape layout (Option A) + book details

**Data.** Add two fields to `PlayerSurfaceState`:

- `facts: String? = null` — a one-line summary built by the view-model, e.g.
  `"Audiobook · 10h 53m · Science Fiction & Fantasy"` (duration + first genre(s); omit empties).
- `description: String? = null` — the book blurb (`item.description`).

`AudiobookPlayerViewModel` already loads the full `LibraryItem`, so it populates both into `meta`.
Both default to null, so nothing else changes.

**Layout.** Thread `windowSizeClass` from `MainScreen` → `AudiobookPlayerScreen`; compute
`twoColumn = heightSizeClass == Compact` (any short window, i.e. a phone in landscape) and pass it to
`PlayerSurface`. The player is full-screen (no drawer either way), so it keys on height alone.

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

# Readaloud highlight color — visibility & saturation

**Date:** 2026-06-16
**Branch:** pkmetski/nagoya
**Status:** Approved design, pending implementation plan

## Problem

The readaloud sentence-highlight color setting (added in #191) has three rough edges:

1. **Selection indicator is hard to read in dark theme.** The selected swatch is marked
   only by a thin 2dp `onSurface` border. Against the dark settings background, a pale
   pastel swatch with a thin light border looks almost identical to the unselected ones —
   you cannot tell at a glance which color is picked.
2. **The palette is washed out.** The five options are near-white pastels
   (`#7DD3FC`, `#FDE68A`, `#86EFAC`, `#FDA4AF`, `#C4B5FD`), which read as low-contrast and
   hard to tell apart.
3. **The highlight is too faint on a dark reading page.** Readium's default
   `Decoration.Style.Highlight` paints the tint as `background-color: rgba(tint, 0.3)` (normal
   blend, a fixed 30% alpha). On the Dark/DarkDim themes the page background is black, so 30% of
   a colour over black reads as a barely-visible dim wash behind the white body text.
   *(Correction: an earlier draft assumed `mix-blend-mode: multiply`; the actual Readium 3.3.0
   template uses a fixed-alpha rgba fill. The fix below is unchanged either way.)*

## Goals

- Make the currently-selected swatch unmistakable in both light and dark app themes.
- Replace the pastels with a more saturated, easier-to-distinguish palette.
- Make the readaloud highlight legible on dark reading pages without harming light/sepia pages,
  and without recoloring the book's own text.

## Non-goals

- Recoloring the book's highlighted **text** glyphs (e.g. forcing black text). Readium draws
  the highlight as a separate layer *behind* the text, not as a wrapper around it, so the only
  way to recolor glyphs is fragile JS span-injection that fights Readium's reflow. Rejected.
- Fixing the same dark-page washout for **persisted** highlights (yellow) and **search**
  highlights (orange/yellow). They share the root cause but are out of scope here; noted as a
  possible follow-up.
- Changing where the selector lives in Settings, its size, or adding labels. Indicator only.

## Design

### 1. Selection indicator — offset ring + checkmark

Replace the thin inset border with a Material-style selection treatment that reads on any
swatch color in any theme:

- **Offset ring:** an outer ring separated from the swatch by a surface-colored gap (so the
  ring always floats clearly, regardless of the swatch's own color). In Compose terms: the
  selected swatch is wrapped so it shows `surface`-colored padding, then a 2dp
  `onSurface` ring around that.
- **Checkmark:** a centered check glyph inside the selected swatch. Because the swatches are
  light/medium in luminance, the check is drawn in a dark color (e.g. `Color(0xDD000000)`) for
  contrast on every option. Unselected swatches show no check and no ring.

This makes "this one is selected" unambiguous even at a glance, in both light and dark.

### 2. Palette — replace pastels with saturated hues

Keep the existing enum **names** (so no persistence migration is needed — `ReadaloudPreferences`
stores the enum name string), and change only the `argb` values:

| Enum entry | Old (pastel) | New (saturated) |
|------------|--------------|-----------------|
| `BLUE`     | `#7DD3FC`    | **`#38BDF8`**   |
| `YELLOW`   | `#FDE68A`    | **`#FBBF24`**   |
| `GREEN`    | `#86EFAC`    | **`#34D399`**   |
| `PINK`     | `#FDA4AF`    | **`#FB7185`**   |
| `PURPLE`   | `#C4B5FD`    | **`#A78BFA`**   |

These are medium-saturation (Tailwind ~400-level): clearly vivid in the selector and on the
page, but not neon, so they still sit behind reading text without crushing legibility.

### 3. Dark-page legibility — theme-aware highlight rendering

Render the readaloud highlight differently depending on the current **reading** theme
(`FormattingPreferences.theme`, which is independent of the app's Material theme):

- **Light / Sepia pages:** keep the vivid `multiply` look — `multiply(lightBackground, tint)`
  shows the saturated tint and the dark book text stays sharp. (Unchanged from today.)
- **Dark / DarkDim pages:** render the highlight as a **translucent "selection box"** — the
  saturated tint at ~45% alpha with `mix-blend-mode: normal`. White book text then sits on a
  tinted block (exactly like a dark-editor text selection) and stays legible. The book's text
  color is untouched.

**Mechanism.** Readium's default highlight style is not theme-aware and its blend mode is fixed
in the template. To control blend mode + alpha we register **custom `HtmlDecorationTemplate`(s)**
on the navigator and select which to use when building the readaloud decoration, keyed off the
current `ReaderTheme`. Concretely:

- Register custom decoration style(s)/template(s): one "vivid/multiply" and one
  "translucent/normal ~45% alpha".
- The `LaunchedEffect` that applies the `readaloud_active` decoration
  (`EpubReaderScreen.kt` ~1393–1427) gains the current reading theme as an input, and chooses
  the multiply style for Light/Sepia or the translucent style for Dark/DarkDim.
- A clean dark signal already exists:
  `theme == ReaderTheme.Dark || theme == ReaderTheme.DarkDim`.

**Implementation fallback (decided at plan time):** if registering and selecting between two
templates proves awkward in Readium, a single uniform translucent normal-blend template
(`rgba(tint, ~0.4)`) is an acceptable simpler alternative — it tints toward whatever the page
background is, so it self-adapts to light and dark. The tradeoff is a slightly paler look on
light pages than today's multiply. Prefer the theme-aware two-template approach; fall back only
if needed.

## Affected code

- `core/domain/.../ReadaloudPreferences.kt` — new `argb` values (names unchanged).
- `app/.../feature/settings/SettingsScreen.kt` — swatch selection indicator (offset ring + check).
- `app/.../feature/reader/EpubReaderScreen.kt` — custom decoration template(s) + theme-aware
  style selection for the `readaloud_active` decoration; thread the reading theme into the
  applying `LaunchedEffect`.
- Possibly a small new file for the custom `HtmlDecorationTemplate` definitions.

## Risks / verification

- **Device verification required.** The dark-page highlight and the custom Readium template can
  only be truly judged on a device/emulator with a real EPUB in Dark and Light reading themes.
  The browser prototype approximates blend behavior but is not authoritative.
- Confirm the saturated palette still reads acceptably behind text on **Sepia** as well as
  Light and Dark.
- Confirm no regression to persisted/search highlights (they keep the default style).
- No DB or persistence migration (enum names unchanged).

## Out of scope / follow-ups

- Theme-aware rendering for persisted and search highlights (same dark-page washout).
- Per-color alpha tuning beyond a single shared dark-page alpha, if it proves necessary.

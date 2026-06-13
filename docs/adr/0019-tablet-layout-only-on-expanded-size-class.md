# ADR 0019 — Tablet Layout only activates on the Expanded window size class

**Status:** Accepted

## Context

Riffle needs to support tablets. Material 3's `WindowSizeClass` partitions windows by width into three buckets:

- **Compact** (< 600dp): phones in portrait.
- **Medium** (600–839dp): phones in landscape, small tablets in portrait, unfolded foldables.
- **Expanded** (≥ 840dp): tablets in landscape, large tablets in portrait, ChromeOS windows, Android split-screen halves on tablets.

The load-bearing decision is what to do with **Medium**, because it contains two structurally different cases: a phone held sideways (still phone-shaped, just wider), and a small tablet held upright (tablet-shaped). Treating Medium as "tablet" gives landscape phones a permanent nav drawer next to a postcard-height reading area. Treating Medium as "phone" leaves a 7–8" tablet in portrait under-utilised. A three-bucket layout (distinct UI per size class) doubles the responsive surface forever.

## Decision

**The Tablet Layout activates only when the window is large in *both* dimensions — the Expanded width class (≥ 840dp) AND a non-Compact height class.** Compact and Medium width both render the standard phone UI, and so does an Expanded-width window that is Compact in height. There is no intermediate Medium-specific variant.

The height guard exists because the width-only rule over-captured one case it was meant to exclude: a **large phone in landscape** crosses 840dp of width, so width alone handed it the permanent drawer and two-pane layouts next to a postcard-height area — the exact outcome the "Decision" was written to avoid. A landscape phone is Compact in height (< 480dp); a real tablet is taller in both orientations. The single predicate `WindowSizeClass.isTabletLayout()` (Expanded width && non-Compact height) encodes this and is the only thing layout code should branch on.

This means:

- Phones in any orientation render the phone UI.
- Small tablets in portrait render the phone UI; in landscape they render the Tablet Layout.
- Unfolded foldables typically render the phone UI (their unfolded width is usually still in Medium).
- ChromeOS and split-screen render whichever layout the actual window width falls into, recomputed on configuration change.

## Alternatives considered

**Two-bucket split at 600dp (Compact = phone, Medium + Expanded = tablet).** Rejected: a phone in landscape (≥ 600dp wide) would get a permanent nav drawer occupying ~30% of the screen next to a 360dp-tall reading surface. The permanent drawer's whole value is "the screen is wide *and* tall enough to spare the chrome." A landscape phone is wide but not tall.

**Three-bucket split (distinct UI per size class).** Rejected: doubles the layout surface to design, test, and maintain forever, in exchange for marginally better fit on the Medium edge case. The realistic delta — a `NavigationRail` and slightly bigger grids on Medium — is not worth the permanent complexity, especially when most Medium windows are landscape phones whose users will likely flip back to portrait to read anyway.

**Index on `smallestScreenWidthDp` (device-shape signal) instead of current window width.** Rejected: it treats a small tablet identically in portrait and landscape, which is wrong — portrait small tablets really do feel cramped under the two-pane Library Item Detail Screen. Indexing on current width via `WindowSizeClass` also handles ChromeOS, split-screen, and foldable resize uniformly without special-casing.

## Consequences

- **Landscape phones do not get the Tablet Layout.** This is deliberate. Users with foldables in particular should expect the phone UI most of the time.
- **Code reads a window-size predicate, not "is this a tablet" as a device fact.** The single helper `WindowSizeClass.isTabletLayout()` (Expanded width && non-Compact height) is evaluated at composition time. It is deliberately phrased about the *layout* the window can host, not the device — the same window predicate keeps behaviour consistent across foldables, ChromeOS, and split-screen, where "tablet" as a noun is meaningless. Do not reintroduce a device-shape `isTablet()`.
- **Configuration changes are load-bearing.** Unfolding, resizing, and rotating all cross the threshold. The framework's automatic `WindowSizeClass` recomputation plus `rememberSaveable` is trusted to preserve in-app state across these transitions. No bespoke handling.
- **Tests need to exercise both sides of the threshold.** A second harness AVD ("Harness Medium Tablet") and a separate `make harness-test-tablet` target cover this — only tests annotated for tablet behaviour run on the tablet AVD, so the full suite does not double-run.

---
name: panel-detector-path-invariant
description: "Panel-detection debugging is guess-free ONLY via the fidelity chain — reproduce the issue's \"Detected panels\" list coordinate-for-coordinate on JVM before touching the algorithm; identify the book by cache-matching, never assume"
metadata: 
  node_type: memory
  type: reference
  originSessionId: fcf40091-d9bf-48fd-941b-66609c17f242
  modified: 2026-09-03T07:06:25.035Z
---

**The 2026-08 #783/#784/#786 fiasco and its actual root causes** (two failed fix rounds, one
shipped regression, verification done in the wrong book):

1. **Reporter masks were unfaithful.** `generateMaskPng` used a hand-rolled full-res decode with
   integer luma `(77r+150g+29b)>>8`, while detection used `AndroidPageImageDecoder` (float BT.601).
   The re-binarized masks differed enough at diagonal/sparse edges (~50px on one panel edge) to
   flip path and threshold decisions: JVM tests on the fixture passed while the device failed.
   FIXED 2026-08-21: `generateMaskPng` now goes through the injected `PageImageDecoder` +
   `PanelMaskBinarizer` — reporter masks are now byte-identical to the detection input.
   (My earlier "binarized mask → CC path, JPEG → PROJ path" theory was itself a guess and wrong —
   detection runs at full resolution since v20, `targetLongEdge=4096`.)

2. **The book was never identified.** Panel reports don't name the book (no-real-titles policy).
   #783/#784 were from a Мики Маус Komga book (1042-wide pages); "device verification" happened
   in Fables (1987-wide pages). Identify the book by matching the issue's "Detected panels"
   against pulled caches: `adb shell run-as com.riffle.app cat files/comic-panels/<id>.json`.

3. **Threshold fixes tested only where they should fire.** Fix B (remainder emission) had no test
   at 53% width where it must NOT fire → #786 regression (sliver panel). Threshold changes need
   both-sides boundary tests, and geometrically-ambiguous signatures need a content-based
   discriminator (see `boundaryContinuesThroughColumn`: a tall panel cut collaterally has artwork
   running through the boundary rows within the column's x-range; a genuine panel boundary is
   white there).

4. **#787 (2026-08-22) was wrongly closed as "already fixed" by assumption — then properly
   fixed the same day** (commit `8acc40693`: remainder left edge from band strip gutter runs,
   overlap-capped so the merge never emits geometry applyGlobalSanityChecks rejects; the
   uncapped first attempt collapsed the whole PROJ result to the CC path). Original error: Its reported
   panels exactly matched the #784 fix's OWN test assertions and the report post-dated the
   fixed build — the fix's output was itself the defect (merge remainder at top.maxX+1 ignores
   the diagonal boundary in the band; user drew x=460, algorithm emits x=592). The failing
   verification test was deleted with a rationalization ("mask must be from the old pipeline")
   when it was actually the fidelity gate passing. Now codified as skill section 2b-agreement:
   run the CURRENT algorithm on the sample, compare against BOTH the reported-bad list and the
   user-drawn list, and check the report's timestamp vs the fix's install time. "Already fixed"
   requires all three to agree.

**Issue #834 fix (2026-08-30):** `diagonalProfileScan` false-negative on falling-right diagonal
with flat-prefix 1-pixel bump. A horizontal gutter row above the diagonal zone generated `dy=0`
profileB entries, then a `dy=1` bump, then the real `dy=8+` rise. The bump anchored `firstRisingIdx`
too early → riseSpan inflated → ratio < 75%. Fix: track `hasFlatPrefix` (any `dy=0` seen); if true,
require `dy ≥ 2` to anchor `firstRisingIdx`. Schema bumped to v34. PR #841.

**Issue #835 (2026-08-30): branch regression FIXED, underlying issue still open.** Device produces
3 panels (row1-left, row1-right, huge-bottom). Branch was producing 2 full-height columns — fixed
2026-08-30 by switching test to `loadBinaryFixture` (commit `90e5bda6d`). Root cause of branch
regression: `loadMaskFixture` double-binarises the fixture (20/240 grid → texture expansion → gap
rows go from 14% to ~95% content, hiding horizontal gutter → CC path → vertical-only split → 2
full-height columns). `loadBinaryFixture` bypasses second binarisation and faithfully reproduces the
device's 3-panel output. REMAINING ISSUE: bottom section (row 2 + scatter) collapses into one
full-width panel because the scatter section's dark background fills the column gutter across its
full height, blocking both projection and flood-fill column detection for the bottom row band.
Fix requires restricting column projection to the non-scatter rows of a suspicious row band.
Fixture at `issue-835-panel-cut-off-p276.png`. Issue still open on GitHub (3→5 panels needed).

**The guess-free procedure** (now codified in the `address-panel-detection-issues` skill,
sections 2b-fidelity / 2b-agreement / 2f-boundary / 2g-device):
- Run the fixture through `detect()` and require coordinate-for-coordinate match (±2px) with the
  issue's "Detected panels" before any diagnosis. Mismatch = broken repro, stop.
- If needed, rebuild the true input: fetch original page from Komga
  (`/api/v1/books/<id>/pages/<pageIndex+1>`), replicate device decode on JVM (float BT.601 luma,
  full res) — this reproduced the device output EXACTLY in 2026-08.
- Verify fixes on device by pulling the regenerated cache JSON and diffing geometry (schema bump
  required: `JsonPanelStore.CURRENT_SCHEMA_VERSION`), sweeping ±5 pages plus any other affected
  book; screenshots are only the final visual confirmation.
- Mask round-trip note: a committed mask fixture reproduces the JPEG pipeline within ±2px
  (binarizer border handling of two-value input), so image-test assertions need small tolerances.

**Issue #905 (2026-09-03): loader choice IS the fidelity gate — neither loader is universally
faithful.** `loadBinaryFixture` (PanelBinaryMask) bypasses binarization AND skips every
luma-dependent stage (`detect(mask)` passes `grid=null`, so `energyValleySplit` never runs) — on
page 34 it produced a "better" result than the device, making the #894 test green on the wrong
pipeline while the device kept failing. `loadMaskFixture` (mask replayed as luma PixelGrid) ran
the grid path and reproduced the device output ±2px. But per #835 (above) the opposite held on a
textured page. Rule: for each new fixture, run BOTH loaders and use whichever reproduces the
issue's "Detected panels" coordinate-for-coordinate; if neither does, fetch the original page and
replicate the decode (that reproduced the device EXACTLY for #905 — byte-identical binarized
mask, exact coordinates). Fix shipped in PR #904 (schema v41): `mergeCrossContainedBboxes`
(cross-contained partial views of one panel union instead of the pillar dying as a sliver) and
`repairMisalignedStackedRowBoundaries` (stacked two-cell rows with misaligned boundaries;
false boundary = padded min-longest-content-run ≥0.65 through the other row, real ≤0.50, plus
artwork must bridge the row gap — staggered grids stay untouched). Device-verified via session
AVD clone + data migration from the dev emulator (keystore-encrypted credentials do NOT migrate:
re-add the source via UI, then repoint `library_items.sourceId` in sqlite to the new source id).

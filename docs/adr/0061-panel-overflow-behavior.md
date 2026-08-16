# ADR 0061 — Panel Overflow behavior for full-axis-span panels

**Date:** 2026-08-16
**Status:** Accepted

## Context

Panel View (ADR 0055) zooms the camera to fill each detected panel in reading order. For panels whose width spans ≈100% of the page (a banner row) the zoom factor `min(vpW/panelDisplayW, vpH/panelDisplayH)` collapses to ≈1.0 — the width constraint wins and the panel renders at fit-whole scale with no magnification. The same problem applies symmetrically in landscape for tall narrow panels.

## Decision

Introduce a **Panel Overflow** setting with three values — **Off**, **Split**, **Auto-rotate** — configurable globally (DataStore `comic_formatting_preferences`) with a per-book override (Room `book_comic_formatting_preferences`, migration 65→66). The setting is visible but disabled when Panel View is off, following the chapter-map dependency pattern.

**Trigger condition:** `panel.axis / imageDimension ≥ 0.9` AND `panel.renderedAxisSpan < viewportOtherDimension`. Symmetric across portrait/landscape:
- Portrait + wide panel (≥90% of image width, rendered width < viewport height)
- Landscape + tall panel (≥90% of image height, rendered height < viewport width)

**Split:** divide the panel at its dead centre into two `PanelRegion`s. Wide panels split left/right; tall panels split top/bottom. The panel list is transformed at display time in the ViewModel via `PanelOverflowTransform`; the stored detected panels are never mutated.

**Auto-rotate:** force `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` (portrait→landscape) or `SCREEN_ORIENTATION_SENSOR_PORTRAIT` (landscape→portrait) for the duration of the overflowing panel. Restore `SCREEN_ORIENTATION_USER` on advance or reader exit. The panel list is not modified for this behavior.

**Default:** Split — it is the less disruptive option and works without any device interaction.

## Alternatives considered

**Content-aware split** (find the column with least visual content inside the panel): deferred as a future improvement. Dead-centre is simpler and covers the common case.

**Height-based zoom with horizontal pan** (zoom to fill viewport height, enable horizontal panning): rejected because it requires enabling pan while Panel View is on, which conflicts with the tap-thirds navigation model.

**Minimum zoom floor** (always zoom at least 1.5×): rejected because it produces arbitrary crops with no reading-order guarantee.

## Consequences

- `PanelOverflowTransform` (pure domain, `commonMain`) owns the detection and split logic, tested in JVM.
- `CbzReaderViewModel` applies the transform at display time and exposes `effectivePanels`; `PanelRegion` data model is unchanged.
- A new Room table `book_comic_formatting_preferences` (migration 65→66) stores per-book overrides for both Panel View and Panel Overflow, superseding the legacy `PanelViewPreferencesStore` DataStore for per-book Panel View. A one-time lazy migration is applied on first book open.
- The Settings "Reading" section is renamed "Books" to distinguish it from the new peer "Comics" section.
- A formatting icon (`ViewQuilt`) in the CBZ reader TopAppBar opens the in-reader `ComicFormattingSheet`, providing per-book override without leaving the reader.

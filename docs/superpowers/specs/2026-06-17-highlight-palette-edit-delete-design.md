# Highlight palette + edit & delete — design (issue #70)

## Goal

Let the reader choose a highlight's colour from a fixed four-token palette and edit
(recolour) or delete an existing highlight. Builds on the create/render path from #69
(PR #80, already on `main`). Per ADR 0024 the colour is a **token from a small fixed
palette — yellow (default), green, blue, pink** — never a freeform hex; the reader theme
owns the light/dark RGB mapping so a highlight stays legible in any theme.

## Context (what #69 left)

- `AnnotationEntity` (`annotations` table) already carries `color` (token string, default
  `"yellow"`), `deleted` tombstone, and `updatedAt`. **No Room migration is needed.**
- `AnnotationStore` has `createHighlight(..., color = DEFAULT_COLOR)` and `delete(id)`.
  There is **no recolour** method, and `createHighlight` is never actually passed a colour.
- The reader UI is gated behind `ANNOTATIONS_UI_ENABLED = false`, held off "until that
  management UI lands."
- Persisted highlights render through a **stub**: `highlightTint()` ignores the token and
  always returns a hardcoded pale yellow, painted with Readium's default
  `Decoration.Style.Highlight` (fixed 0.3 alpha — *not* theme-aware, illegible on dark).
- The readaloud highlight (PR #191/#197) already solved dark legibility with a dedicated
  tinted-box decoration style + per-theme alpha. That machinery is what we reuse.

## Decisions

- **Reuse the colours, not the type.** The four annotation hues match readaloud's exactly
  (visual consistency) but live in their own self-contained `HighlightColor` enum in
  `core/domain` — annotations do not reference `ReadaloudHighlightColor`. The two features
  have different vocabularies (readaloud has PURPLE), defaults (BLUE vs yellow), and
  persistence/sync domains.
- **Generalise the rendering machinery.** Rename the readaloud decoration style + template
  to a neutral shared name and let both paths use it (mechanical rename, identical
  behaviour). This is the consistency win and avoids two near-identical templates.
- **One unified "Highlight actions" sheet** for both create and edit, satisfying both
  "swatch row offered when creating" and "tapping surfaces recolour/delete."
- **Create-as-yellow-then-open-sheet.** Creating commits a yellow highlight immediately,
  then opens the actions sheet so the user can recolour or delete right away.

## Layers

### 1. Shared colour token (`core/domain`)

New `HighlightColor` enum — the shared location for the four annotation colours,
self-contained, using the same hues as readaloud:

```
enum class HighlightColor(val token: String, @ColorInt val argb: Int) {
    YELLOW("yellow", 0xFFFBBF24.toInt()),  // default
    GREEN ("green",  0xFF34D399.toInt()),
    BLUE  ("blue",   0xFF38BDF8.toInt()),
    PINK  ("pink",   0xFFFB7185.toInt());
    companion object {
        val DEFAULT = YELLOW
        fun fromToken(token: String?): HighlightColor  // unknown/null → DEFAULT (sync forward-compat)
    }
}
```

`AnnotationEntity.COLOR_YELLOW` / `AnnotationStore.DEFAULT_COLOR` stay `"yellow"` and remain
the persisted default. The entity keeps storing the lowercase token string.

### 2. Recolour in the data layer

- `AnnotationDao.recolor(id, color, updatedAt, deviceId)` →
  `UPDATE annotations SET color = :color, updatedAt = :updatedAt, lastModifiedByDeviceId = :deviceId WHERE id = :id`
  (mirrors the existing `tombstone` query; bumps `updatedAt`).
- `AnnotationStore.recolor(id: String, color: String)` + `AnnotationStoreImpl`
  implementation (stamps `clock()` + device id). Delete already exists.

### 3. Theme-aware rendering (`app`) — the reuse

- Rename `ReadaloudHighlightStyle` → `HighlightTintStyle`; `readaloudHighlightTemplate()` →
  `highlightTintTemplate()`; the CSS class to a neutral name. Update the registration in
  `FormattingPreferencesMapper` and the readaloud apply-site (mechanical; behaviour
  unchanged).
- Extract the per-theme alpha logic into `tintForTheme(@ColorInt argb, theme): Int` holding
  the existing `0x73` dark / `0x4D` light alphas. Both `HighlightColor.readerTint(theme)`
  and `ReadaloudHighlightColor.readerTint(theme)` delegate to it.
- The "annotations" decoration group switches from the stub `Decoration.Style.Highlight`
  to `HighlightTintStyle(tint = HighlightColor.fromToken(h.color).readerTint(theme))`. The
  stub `highlightTint()` is deleted. The annotations decoration LaunchedEffect re-keys on
  the reader theme so a theme change re-tints existing highlights.

### 4. Reader UI: create + tap-to-edit/delete (`app`)

- Flip `ANNOTATIONS_UI_ENABLED = true`.
- A shared `HighlightActionsSheet` (ModalBottomSheet): a 4-swatch `HighlightSwatchRow`
  (selected swatch gets the offset-ring + centred checkmark treatment, modelled on the
  readaloud picker) + a **Delete** button.
  - **Create:** selection "Highlight" menu → `createHighlight` (yellow) → open the sheet on
    the new highlight's id.
  - **Edit:** register a `DecorationListener` on the "annotations" group via
    `addDecorationListener`; `onDecorationActivated` opens the sheet for the tapped
    decoration's id.
- Extract a small generic `HighlightSwatchRow` composable (over argb + selection) for the
  sheet. Leave the existing readaloud settings picker untouched (scope containment).

### 5. ViewModel

- `createHighlight(selectionLocator)` already returns the created `Annotation`; surface its
  id so the screen can open the sheet.
- Add `recolor(id, HighlightColor)` and `deleteHighlight(id)` delegating to the store.
- `HighlightRender.color` (already `String`) feeds the tint.

### 6. Tests

- `HighlightColor` token round-trip + unknown → yellow.
- Store/DAO: recolour updates `color` **and** bumps `updatedAt`; tombstoned rows excluded
  from `observeHighlights` (extends existing `AnnotationDaoTest`).
- ViewModel: recolour and delete reflected in `highlightRenders`.

### 7. Docs

- Amend ADR 0024 with the concrete four-token palette + shared theme-aware rendering
  decision.

## Acceptance criteria (from #70)

- [ ] Four-swatch palette (yellow/green/blue/pink) offered on create and edit; yellow default.
- [ ] Colour stored as a token; rendered RGB adapts in light and dark themes.
- [ ] Recolouring updates in place and bumps `updatedAt`.
- [ ] Deleting soft-deletes (tombstone + `updatedAt`), removes the decoration, stays gone after reopen.
- [ ] Tests cover colour-token persistence/round-trip and tombstoned highlights excluded from rendering.

## Out of scope

- Notes (the `note` field stays unused).
- Annotation sync (ADR 0025 ships local-only in v1).
- Refactoring the readaloud settings picker to the new shared composable.

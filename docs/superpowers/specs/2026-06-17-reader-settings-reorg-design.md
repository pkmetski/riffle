# Reader settings reorganization — design

Date: 2026-06-17

## Problem

Reader formatting and related settings are crammed into a single surface. Today
one composable — `FormattingPanel` — renders ~15 control groups in one scroll and
is reused two ways: as the in-reader half-sheet (`fullScreen = false`) and as the
global Settings → Reading → Formatting screen (`fullScreen = true`). Unrelated
controls share space (e.g. "Keep screen on" sits beside "Justify text"), making
both surfaces overwhelming and illogically grouped.

## Goals

- Group reader settings into logical categories.
- Keep the in-reader panel exposing **everything** it does today (no controls
  removed from the reader) — only reorganized.
- Preserve the single-source-of-truth property: a setting is defined in **one**
  place and appears on both surfaces.
- Fix the existing wart where a permanently-disabled "Reset to global defaults"
  button shows on the global Settings surface.

## Taxonomy — three categories

| Category | Controls |
|----------|----------|
| **Formatting** | Font size, Font, Justify text, Line spacing, Margins |
| **Display** | Theme (+ Auto day/night schedule), Reading mode (Paginated/Scroll), Double-page in landscape, Chapter map, Current chapter label, Reading progress labels, Time remaining |
| **Behavior** | Keep screen on, Volume-key navigation, Invert volume keys |

Notes:
- **Rename** the old "Formatting"/"Appearance" concept: the reader-content category
  is **Formatting**. This avoids collision with the existing top-level Settings
  **Appearance** section (app-chrome theme: Light/Dark/System).
- **Double-page** lives in Display because it is mechanically coupled to Reading
  mode (disabled in Scroll).
- **Auto schedule** allows **any** theme (Light/Dark/Dim/Sepia) for both day and
  night — matches current behavior, made explicit.

## Architecture — one taxonomy, two presentations

Replace the monolithic `FormattingPanel` with **three stateless section
composables** (the single source of truth), consumed by **two thin host layouts**
that define no controls of their own.

### Section composables

```
FormattingSection(prefs, onPrefsChange)
DisplaySection(prefs, onPrefsChange, scheduleEditable: Boolean)
BehaviorSection(keepScreenOn, onKeepScreenOnChange,
                volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange,
                invertVolumeKeys, onInvertVolumeKeysChange)
```

- Each section owns its control rows **and** its internal sub-headers
  (Formatting: Text/Page; Display: Theme/View/On-screen info).
- Leaf primitives (`StepperRow`, chip rows, switch rows) remain shared.
- Sections are stateless `(state, onChange)` — the **host** supplies the data
  source. Reader feeds per-book-merged prefs and writes per-book overrides;
  Settings feeds global prefs and writes globals. Behavior toggles are global in
  both.

### Host-specific differences (explicitly flagged, never forked)

- `DisplaySection(scheduleEditable)` — the Auto day/night **schedule editor** is
  rendered only when `scheduleEditable = true`. Reader passes `false` and shows a
  compact **read-only summary** when Auto is selected ("Day 7:00 · Light → Night
  21:00 · Dark" + "Edit the schedule in Settings → Display"); Settings passes
  `true` and shows the full editor (day/night start times + day/night theme
  pickers, each offering all four themes).

### Reader host — `ReaderSettingsSheet`

- Opened from the reader top bar via the existing **"Aa"** icon (genre-standard;
  Kindle/Apple Books/Kobo), defaulting to the **Formatting** tab.
- Fixed-height bottom sheet (does not resize when switching tabs; never covers
  more than ~60% so the page stays visible to preview changes).
- A segmented tab bar — **Formatting / Display / Behavior** — swaps which section
  is shown. Each tab body is the corresponding section composable.
- Host-owned chrome: the **"Reset to global defaults"** button (reader-only;
  enabled when the book has overrides). This removes the dead button from
  Settings.

### Settings host — three drill-in screens

- Settings home gains a **Reading** group with three rows: **Formatting**,
  **Display**, **Behavior**, each with a value-preview subtitle.
- Each row navigates to a focused screen (Scaffold + TopAppBar) whose body is the
  one corresponding section composable. Display passes `scheduleEditable = true`.

## What this fixes (current divergences, from code audit)

Audited `FormattingPanel.kt` + both call sites. Beyond presentation (half-sheet vs
full-screen), today's two surfaces differ in exactly:
1. **Auto schedule editor** — `fullScreen`-only. → handled by `scheduleEditable`.
2. **"Reset to global defaults"** — rendered on both but permanently disabled and
   no-op in Settings (`hasBookOverrides = false`, `onReset = {}`). → moved to the
   reader host only; gone from Settings.
3. **Data source** — per-book overrides vs global (intended). → host supplies data
   source; sections stay stateless.

Everything else is identical and remains so by construction (shared sections).

## Non-goals

- No change to the underlying preference stores, DataStore keys, Room schema, or
  per-device scoping. Pure UI/composition reorganization.
- No new settings; no behavior change beyond removing the dead Reset button from
  Settings and surfacing the read-only Auto summary in the reader.

## Testing

- Unit/Compose tests asserting each section renders its controls and emits the
  right `onChange` (reused across both hosts).
- A test that the reader sheet shows all three tabs and that Behavior is reachable
  while reading.
- A test that Settings → Display shows the schedule editor and the reader does
  not (read-only summary instead) when theme is Auto.
- A test that Settings no longer shows the Reset button.
- Run `./gradlew test`; harness tests via `make harness-test` where UI flows are
  affected.

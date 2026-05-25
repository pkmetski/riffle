# Design: Global Settings in Reader Formatting Panel

**Date:** 2026-05-25  
**Status:** Approved

## Problem

Screen Wake ("Keep screen on") and Volume Key navigation settings are reading-relevant but only accessible via the global Settings screen. Users must leave the reader to change them. The per-book `FormattingPanel` is the natural place to surface them.

## Decision

Surface Screen Wake and Volume Key settings in the `FormattingPanel` bottom sheet as a clearly labeled "Also while reading" section below the per-book settings. Changes write directly to the global DataStore stores — they remain global (not per-book overrides). The settings also stay in the global Settings screen; the panel is an additional access point.

## UI Layout

```
FormattingPanel (ModalBottomSheet)
├── [per-book settings: font size, theme, font family,
│    line spacing, margins, orientation, chapter map]
├── [Reset to global defaults] button  ← moved above divider
├── ─────────── Also while reading ───────────
├── Keep screen on              [toggle]
│   Applies to all books
├── Volume key navigation       [toggle]
│   Applies to all books
└── Invert volume keys          [toggle, disabled when vol nav off]
    Applies to all books
```

- Section label rendered as a `HorizontalDivider` with centered `Text` — matches Material 3 section header pattern already used in `SettingsScreen`.
- "Applies to all books" is the `supportingContent` on each `ListItem`, making scope always visible without needing tags.
- "Reset to global defaults" moves up to sit before the divider so it is unambiguously scoped to per-book formatting only.

## Component Changes

### `FormattingPanel.kt`

Add parameters:
```kotlin
keepScreenOn: Boolean,
onKeepScreenOnChange: (Boolean) -> Unit,
volumeKeyNavigationEnabled: Boolean,
onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit,
invertVolumeKeys: Boolean,
onInvertVolumeKeysChange: (Boolean) -> Unit,
```

Add UI below the existing content (after the Reset button, which moves up):
- `HorizontalDivider` with "Also while reading" label
- Three `ListItem` rows with `Switch` trailing content
- "Invert volume keys" row uses `alpha(if (volumeKeyNavigationEnabled) 1f else 0.38f)` and its switch is `enabled = volumeKeyNavigationEnabled`, matching the existing global settings behavior

### `EpubReaderViewModel.kt` and `PdfReaderViewModel.kt`

Both already inject `WakeLockPreferencesStore` and `VolumeKeyPreferencesStore`. Add setter methods:
```kotlin
fun setKeepScreenOn(value: Boolean) = viewModelScope.launch { wakeLockPrefs.setKeepScreenOn(value) }
fun setVolumeKeyNavigationEnabled(value: Boolean) = viewModelScope.launch { volumeKeyPrefs.setVolumeKeyNavigationEnabled(value) }
fun setInvertVolumeKeys(value: Boolean) = viewModelScope.launch { volumeKeyPrefs.setInvertVolumeKeys(value) }
```

These delegate to the same stores `SettingsViewModel` uses — global Settings screen stays in sync automatically with no extra work.

### `EpubReaderScreen.kt` and `PdfReaderScreen.kt`

Collect the new state from the ViewModel (already collected for wake lock / volume key handling) and pass values + callbacks into `FormattingPanel`. No change to the existing `DisposableEffect` or `VolumeKeyEventHandler` logic.

## What Does NOT Change

- Global `SettingsScreen` — no modifications; the settings remain there unchanged.
- Per-book override system (`BookFormattingPreferencesStore`, `hasBookOverrides`, `resetToGlobalDefaults`) — untouched. Screen Wake and Volume Keys are not per-book overrides.
- `VolumeKeyEventHandler` — no changes.
- Wake lock `DisposableEffect` — no changes.

## Out of Scope

- Making Screen Wake or Volume Key navigation per-book overrides (future consideration).
- PDF reader formatting panel — apply the same changes as EPUB for consistency.

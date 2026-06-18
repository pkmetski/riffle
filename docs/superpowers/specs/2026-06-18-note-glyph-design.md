# Note glyph in highlight margin — design spec

**Issue:** #206
**Date:** 2026-06-18
**Status:** approved

## Problem

Noted highlights currently show a subtle `Decoration.Style.Underline` in the `"annotation-notes"` group. The underline is easy to miss and conflates the note affordance with the highlight fill. Additionally, tapping the note row in the highlight actions popup opens `NoteEditorDialog` immediately, forcing the user into edit mode even when they only want to read the note.

## Goals

1. Replace the underline with a small semi-transparent note icon rendered in the left gutter next to any highlight that carries a note.
2. Tapping the glyph opens the highlight actions popup (same as tapping the highlight text).
3. Reading a note does not require entering edit mode — a read-first expand-in-place flow precedes the editor.

## Non-goals

- Changing the note data model or sync behaviour.
- Supporting RTL gutter placement (deferred).
- Animating the popup expansion.

---

## Section 1: Decoration system changes

### `NoteGlyphStyle`

New file: `app/src/main/kotlin/com/riffle/app/feature/reader/NoteGlyphDecoration.kt`

```kotlin
class NoteGlyphStyle : Decoration.Style, Parcelable {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    override fun equals(other: Any?) = other is NoteGlyphStyle
    override fun hashCode() = NoteGlyphStyle::class.hashCode()
    companion object {
        @JvmField val CREATOR: Parcelable.Creator<NoteGlyphStyle> = ...
    }
}
```

A marker style — no tint or data fields. Value equality is trivially true between instances (all noted highlights get the same icon).

### `noteGlyphTemplate()`

Lives in `NoteGlyphDecoration.kt`.

```kotlin
fun noteGlyphTemplate(): HtmlDecorationTemplate = HtmlDecorationTemplate(
    layout = HtmlDecorationTemplate.Layout.BOUNDS,
    element = { _ -> """<div class="$NOTE_GLYPH_CLASS"/>""" },
    stylesheet = """
        .$NOTE_GLYPH_CLASS {
            background: none;
            overflow: visible;
            position: relative;
        }
        .$NOTE_GLYPH_CLASS::before {
            content: "";
            position: absolute;
            right: 100%;
            top: 0;
            margin-right: 4px;
            width: 16px;
            height: 16px;
            background-image: url("data:image/svg+xml,..."); /* Material StickyNote2 */
            background-size: contain;
            background-repeat: no-repeat;
            opacity: 0.55;
        }
    """.trimIndent(),
)
```

`Layout.BOUNDS` renders one transparent `div` spanning the entire selection bounding box. The `::before` pseudo-element is positioned `right: 100%; top: 0` — it exits the div to the left and sits in the gutter, aligned with the top of the first text line. `overflow: visible` on the host div prevents clipping at the column edge.

The SVG data URI embeds the Material `StickyNote2` outlined icon at 16×16, monochrome (`currentColor` or explicit `#000`). Opacity 0.55 gives the semi-transparent look in both light and dark themes.

### `FormattingPreferencesMapper.kt`

Add one line in `toFragmentConfiguration()`:

```kotlin
decorationTemplates = HtmlDecorationTemplates.defaultTemplates().apply {
    set(HighlightTintStyle::class, highlightTintTemplate())
    set(NoteGlyphStyle::class, noteGlyphTemplate())   // ← new
},
```

### `EpubReaderScreen.kt` — `"annotation-notes"` LaunchedEffect

Replace `Decoration.Style.Underline(tint = ...)` with `NoteGlyphStyle()`. The tint parameter is no longer needed since the icon is monochrome + opacity-based.

```kotlin
val noteDecorations = noted.map { h ->
    Decoration(
        id = h.id,
        locator = h.locator,
        style = NoteGlyphStyle(),
    )
}
```

### New `"annotation-notes"` decoration tap listener

Add a `DisposableEffect` parallel to the existing `"annotations"` listener:

```kotlin
DisposableEffect(fragmentRef.value) {
    val fragment = fragmentRef.value as? DecorableNavigator
    val listener = object : DecorableNavigator.Listener {
        override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
            if (event.group != "annotation-notes") return false
            val container = containerRef.value ?: return false
            val rawRect = event.rect ?: return false
            val rect = rawRect.toWindowIntRect(container)
            currentOnOpenHighlightActions(event.decoration.id, rect)
            return true
        }
    }
    fragment?.addDecorationListener("annotation-notes", listener)
    onDispose { fragment?.removeDecorationListener(listener) }
}
```

The underline's implicit tap fallthrough to the `"annotations"` listener is removed (the glyph is in the margin, outside the text hit area), so this dedicated listener is required.

---

## Section 2: Read-first note flow

### `HighlightActionsPopup`

Add local state:

```kotlin
var noteExpanded by remember { mutableStateOf(false) }
```

#### Note row — no note ("Add note")
Behaviour unchanged: tapping calls `onDismiss(); onOpenNoteEditor()`.

#### Note row — has note, not expanded
- Label: "Note"
- 2-line ellipsised preview (unchanged)
- Trailing icon: `Icons.Filled.KeyboardArrowDown` (replaces `Icons.Outlined.Edit`)
- Tapping: `noteExpanded = true`

#### Note row — has note, expanded
The Column in the row grows to show:
1. Row header: "Note" label + `Icons.Filled.KeyboardArrowUp` trailing icon (tapping collapses)
2. `Text(text = note, style = bodyMedium)` — non-editable, no max lines
3. `TextButton("Edit")` aligned end — tapping calls `onDismiss(); onOpenNoteEditor()`

`NoteEditorDialog` is unchanged — the edit path is identical to today, just gated behind the expand step when a note exists.

---

## Section 3: Testing

### Unit tests (`app/src/test/`)

- `NoteGlyphStyleTest`: equality (two instances equal, unequal to `HighlightTintStyle`), `Parcelable` round-trip.
- `NoteGlyphDecorationTest`: snapshot assert that `noteGlyphTemplate()` HTML contains `::before`, `right: 100%`, and the SVG data URI prefix `data:image/svg+xml`.

### Compose tests (`app/src/androidTest/` or Robolectric in `app/src/test/`)

Extend or create `HighlightActionsSheetTest`:

| # | Scenario | Assert |
|---|----------|--------|
| 1 | Popup with existing note, not expanded | Chevron-down visible, "Edit" icon absent, preview text shown (max 2 lines) |
| 2 | Tap note row with existing note | Chevron-up visible, full note text visible, "Edit" button visible |
| 3 | Tap "Edit" in expanded state | `onOpenNoteEditor` lambda invoked |
| 4 | Tap "Add note" (no note) | `onOpenNoteEditor` lambda invoked immediately (no expand step) |

### Harness test (`make harness-test`)

One `@Test` in the annotation harness class:
- Load a book with a noted highlight.
- Assert `"annotation-notes"` decoration group is non-empty.
- Tap the glyph area (margin-left of the highlight's bounding rect).
- Assert highlight actions popup appears.

---

## Affected files

| File | Change |
|------|--------|
| `app/.../reader/NoteGlyphDecoration.kt` | New — `NoteGlyphStyle` + `noteGlyphTemplate()` |
| `app/.../reader/FormattingPreferencesMapper.kt` | Register `NoteGlyphStyle` template |
| `app/.../reader/EpubReaderScreen.kt` | Swap `Underline` → `NoteGlyphStyle`; add `"annotation-notes"` listener |
| `app/.../reader/HighlightActionsSheet.kt` | Inline-expand note row |
| `app/src/test/.../NoteGlyphDecorationTest.kt` | New unit tests |
| `app/src/test/.../HighlightActionsSheetTest.kt` | New/extended Compose tests |

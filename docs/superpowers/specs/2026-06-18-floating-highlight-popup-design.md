# Floating Highlight Action Popup — Design

**Issue:** #204  
**Date:** 2026-06-18  
**Branch:** pkmetski/brisbane

## Problem

The current `HighlightActionsSheet` is a `ModalBottomSheet` that slides up from the bottom edge, disconnected from where the highlight actually is on the page. The goal is to replace it with a small floating card that appears near the highlighted text — both when a new highlight is created and when an existing one is tapped.

## Decisions

| Question | Decision |
|---|---|
| Note row in popup? | Yes — same swatches + delete + note row as the current sheet |
| Note editor interaction | Tapping the note row dismisses the popup, then opens the existing `NoteEditorDialog` (AlertDialog) |
| Note reading (full text without edit mode) | Deferred to issue #206, which has been updated with this intent |
| Anchor rect storage | Approach A — ViewModel holds `HighlightEditTarget(id, anchorRect: IntRect)` |
| Popup dismissal on outside tap | `PopupProperties(focusable = true)` |

## Design

### 1. Data model

```kotlin
// In EpubReaderViewModel
data class HighlightEditTarget(val id: String, val anchorRect: IntRect)

private val _highlightToEdit = MutableStateFlow<HighlightEditTarget?>(null)
val highlightToEdit: StateFlow<HighlightEditTarget?> = _highlightToEdit

fun openHighlightActions(id: String, anchorRect: IntRect) {
    _highlightToEdit.value = HighlightEditTarget(id, anchorRect)
}
fun dismissHighlightActions() { _highlightToEdit.value = null }
```

`IntRect` is `androidx.compose.ui.unit.IntRect` — window-pixel coordinates. The `HighlightEditTarget` type propagates through the composable parameter chain wherever `highlightToEdit: String?` appeared before.

`createHighlight` gains an `anchorRect: IntRect` parameter and forwards it to `openHighlightActions`.

### 2. Coordinate mapping

A private top-level extension in `EpubReaderScreen.kt` converts a WebView-local `RectF` to window pixels using the `ScrollBoundaryNavigationContainer` as the reference view:

```kotlin
private fun RectF.toWindowIntRect(view: View): IntRect {
    val loc = IntArray(2)
    view.getLocationOnScreen(loc)
    return IntRect(
        left   = loc[0] + left.roundToInt(),
        top    = loc[1] + top.roundToInt(),
        right  = loc[0] + right.roundToInt(),
        bottom = loc[1] + bottom.roundToInt(),
    )
}
```

**Tap existing highlight** — inside `onDecorationActivated`:
```kotlin
val rect = event.rect.toWindowIntRect(containerRef.value ?: return false)
currentOnOpenHighlightActions(event.decoration.id, rect)
```

**New highlight** — after `selectable.currentSelection()`:
```kotlin
val rect = selection.rect.toWindowIntRect(containerRef.value ?: return@launch)
currentOnHighlight(selection.locator, rect)
```

Note: after rotation the ViewModel survives with a stale `anchorRect`. The popup re-opens at approximately the correct position, which is acceptable.

### 3. Popup card layout

`HighlightActionsSheet` (the `ModalBottomSheet` composable) is replaced by `HighlightActionsPopup` in `HighlightActionsSheet.kt`. Card structure top-to-bottom:

- `Row`: `HighlightSwatchRow` + delete `IconButton` — `padding(horizontal=16.dp, vertical=12.dp)`
- `HorizontalDivider`
- `Row`: note label + 2-line truncated preview if note exists + edit icon — `padding(horizontal=16.dp, vertical=14.dp)`, `clickable` → dismiss popup then open `NoteEditorDialog`

Wrapped in:
```kotlin
Surface(
    shape = RoundedCornerShape(12.dp),
    shadowElevation = 4.dp,
    tonalElevation = 0.dp,
)
```

Fixed width: `280.dp`.

### 4. PopupPositionProvider

```kotlin
private class HighlightPopupPositionProvider(
    private val anchorRect: IntRect,
    private val margin: Int,  // px
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val preferredTop = anchorRect.top - popupContentSize.height - margin
        val top = if (preferredTop >= margin) {
            preferredTop
        } else {
            anchorRect.bottom + margin  // flip below
        }
        // anchorBounds (the Compose anchor) is intentionally ignored — anchorRect carries
        // the WebView-space rect already mapped to window coordinates.
        val centreX = anchorRect.center.x - popupContentSize.width / 2
        // Guard: on a very narrow screen the max bound could be less than margin.
        val minLeft = margin
        val maxLeft = maxOf(margin, windowSize.width - popupContentSize.width - margin)
        val left = centreX.coerceIn(minLeft, maxLeft)
        return IntOffset(left, top)
    }
}
```

Instantiated via `remember(anchorRect) { HighlightPopupPositionProvider(anchorRect, margin) }` where `margin` is `(8.dp).roundToPx()`.

### 5. Note tap flow & dismissal

- **Outside tap / back gesture:** `PopupProperties(focusable = true)` — Compose closes the popup before propagating the event to the reader.
- **Note row tap:** calls `onDismissHighlightActions()` first, then sets `noteEditorOpen = true`. The popup is gone before the `NoteEditorDialog` appears — no layering issue.
- **Color pick / delete:** unchanged — call through to existing ViewModel functions.

### 6. Testing

**Unit tests:**
- `RectF.toWindowIntRect()` — mock `View.getLocationOnScreen()` returning known offsets; assert pixel arithmetic
- `HighlightPopupPositionProvider.calculatePosition()` — pure function; test above/below flip threshold and left/right clamp boundary

**Harness tests:**
- `AnnotationReopenInstrumentedTest` covers the open/close lifecycle; verify it does not assert on `ModalBottomSheet`-specific semantics during implementation

**Manual verification:** exact pixel position on a real device.

## Files changed

| File | Change |
|---|---|
| `EpubReaderViewModel.kt` | `HighlightEditTarget` data class; `_highlightToEdit` type; `openHighlightActions` signature; `createHighlight` signature |
| `HighlightActionsSheet.kt` | Remove `HighlightActionsSheet`; add `HighlightActionsPopup` + `HighlightPopupPositionProvider` |
| `EpubReaderScreen.kt` | `toWindowIntRect` helper; pass `anchorRect` in both trigger paths; update parameter types throughout |

## Out of scope

- Note reading without entering edit mode → issue #206
- Rotation re-anchor (stale rect after config change is acceptable)

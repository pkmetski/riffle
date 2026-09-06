# 13 — Reader Highlights & Bookmarks (iOS)

## 13.1 Tapping "Add note" with no existing note calls onOpenNoteEditor directly

**Given** the highlight actions popup is open for a highlight with no note.
**When** the user taps "Add note".
**Then** the note editor is opened immediately (onOpenNoteEditor callback fires).

**Coverage:** `HighlightActionsPopupTest.noNote_tapNoteRow_callsOnOpenNoteEditor`

## 13.2 Existing note shows preview and expand icon

**Given** the highlight actions popup is open for a highlight that has an existing note.
**Then** the note preview text and an "Expand note" icon are visible.

**Coverage:** `HighlightActionsPopupTest.existingNote_initialState_showsPreviewAndExpandIcon`

## 13.3 Tapping note row expands to full text and Edit button

**Given** the highlight popup has an existing note.
**When** the user taps the note row.
**Then** the full note text and an "Edit" button are visible; a "Collapse note" icon appears.

**Coverage:** `HighlightActionsPopupTest.existingNote_tapRow_expandsToFullTextAndEditButton`

## 13.4 Tapping Edit in expanded state calls onOpenNoteEditor

**Given** the note row is expanded.
**When** the user taps "Edit".
**Then** onOpenNoteEditor is called.

**Coverage:** `HighlightActionsPopupTest.existingNote_expanded_tapEditButton_callsOnOpenNoteEditor`

## 13.5 Note-only mode hides colour swatches and delete button

**Given** the popup is in note-only mode (e.g. for a figure/image annotation).
**Then** colour swatches and the delete button are not visible; only note content and Edit are shown.

**Coverage:** `HighlightActionsPopupTest.noteOnly_hidesColorRowAndShowsNoteDirectly`

## 13.6 Note-only mode with null note shows label but no Edit button

**Given** the popup is in note-only mode but the note has just been deleted (race: glyph still visible).
**Then** the "Note" label is visible but no "Edit" button is shown.

**Coverage:** `HighlightActionsPopupTest.noteOnly_nullNote_showsLabelButNoEditButton`

## 13.7 Note editor auto-focuses the text field when opened

**Given** the note editor dialog opens.
**Then** the text input field is immediately focused (keyboard appears).

**Coverage:** `HighlightActionsPopupTest.noteEditor_autofocusesTextFieldWhenOpened`

**iOS gap:** UIKit keyboard focus behaviour; verified manually via Xcode build.

## 13.8 Bookmark indicator hidden when not visible

**Given** `isVisible = false` regardless of bookmark state.
**Then** no bookmark indicator is rendered.

**Coverage:** `CornerBookmarkIndicatorTest.notVisible_rendersNothing`

## 13.9 Bookmark indicator shows prompt when page is not bookmarked

**Given** `isVisible = true`, `isBookmarked = false`.
**Then** the "Bookmark this page" affordance is visible.

**Coverage:** `CornerBookmarkIndicatorTest.visible_notBookmarked_showsBookmarkPrompt`

## 13.10 Bookmark indicator shows remove prompt when page is bookmarked

**Given** `isVisible = true`, `isBookmarked = true`.
**Then** the "Remove bookmark" affordance is visible.

**Coverage:** `CornerBookmarkIndicatorTest.visible_bookmarked_showsRemovePrompt`

## 13.11 Tapping bookmark indicator fires toggle callback

**Given** the bookmark indicator is visible.
**When** the user taps it.
**Then** the toggle callback is invoked, regardless of current bookmark state.

**Coverage:** `CornerBookmarkIndicatorTest.tap_firesToggleCallback`, `CornerBookmarkIndicatorTest.tap_bookmarked_firesToggleCallback`
